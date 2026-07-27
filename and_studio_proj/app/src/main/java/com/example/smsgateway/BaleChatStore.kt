package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.BaleChatContactEntity
import com.example.smsgateway.data.BaleMessageEntity
import com.example.smsgateway.data.BaleStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class BaleChatItem(
    val messageId: String,
    val chatId: String,
    val senderId: String? = null,
    val text: String,
    val timestamp: Long,
    val direction: String = DIRECTION_INCOMING,
    val phoneNumber: String? = null,
    val senderName: String? = null,
) {
    companion object {
        const val DIRECTION_INCOMING = "incoming"
        const val DIRECTION_OUTGOING = "outgoing"
    }
}

class BaleChatStore private constructor(
    private val appContext: Context,
    private val database: GatewayDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableSetOf<() -> Unit>()

    fun syncPersistenceOnStartup() {
        scope.launch {
            syncOffsetBetweenStores()
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun getMessages(limit: Int = DEFAULT_UI_LIMIT): List<BaleChatItem> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.baleMessageDao().getLatest(limit).map { it.toChatItem() }.asReversed()
            }
        }
    }

    fun getMessagesSince(since: Long, limit: Int): List<BaleChatItem> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.baleMessageDao().getSince(since, limit).map { it.toChatItem() }
            }
        }
    }

    fun getContactPhone(chatId: String): String? {
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.baleChatContactDao().get(chatId)?.phoneNumber?.takeIf { it.isNotBlank() }
            }
        }
    }

    fun isPhonePaired(phone: String): Boolean {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        if (normalized.isEmpty()) return false
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.baleChatContactDao().countByPhone(normalized) > 0
            }
        }
    }

    fun needsPhoneRequest(chatId: String): Boolean {
        return runBlocking {
            withContext(Dispatchers.IO) {
                val contact = database.baleChatContactDao().get(chatId)
                val phone = contact?.phoneNumber?.takeIf { it.isNotBlank() }
                phone == null && contact?.lastPhoneRequestAt == null
            }
        }
    }

    fun markPhoneRequestSent(chatId: String) {
        runBlocking {
            withContext(Dispatchers.IO) {
                val contactDao = database.baleChatContactDao()
                val existing = contactDao.get(chatId)
                val now = System.currentTimeMillis()
                contactDao.upsert(
                    BaleChatContactEntity(
                        chatId = chatId,
                        phoneNumber = existing?.phoneNumber,
                        senderName = existing?.senderName,
                        updatedAt = now,
                        lastPhoneRequestAt = now,
                    ),
                )
            }
        }
    }

    fun getNextOffsetId(settingsStore: SettingsStore): String? {
        return runBlocking {
            withContext(Dispatchers.IO) {
                val roomOffset = database.baleStateDao().get()?.nextOffsetId?.takeIf { it.isNotBlank() }
                val prefOffset = settingsStore.getBaleNextOffsetId()
                roomOffset ?: prefOffset
            }
        }
    }

    fun setNextOffsetId(settingsStore: SettingsStore, offsetId: String?) {
        runBlocking {
            withContext(Dispatchers.IO) {
                val normalized = offsetId?.trim().orEmpty().takeIf { it.isNotBlank() }
                database.baleStateDao().upsert(
                    BaleStateEntity(nextOffsetId = normalized),
                )
                settingsStore.setBaleNextOffsetId(normalized)
            }
        }
    }

    fun appendUpdates(
        updates: List<BaleApi.BaleMessageUpdate>,
    ): List<BaleApi.BaleMessageUpdate> {
        if (updates.isEmpty()) return emptyList()
        return runBlocking {
            withContext(Dispatchers.IO) {
                val messageDao = database.baleMessageDao()
                val contactDao = database.baleChatContactDao()
                val added = mutableListOf<BaleApi.BaleMessageUpdate>()
                updates.forEach { update ->
                    val resolved = resolveContact(update, contactDao)
                    val rowId = messageDao.insert(
                        BaleMessageEntity(
                            messageId = update.messageId,
                            chatId = update.chatId,
                            senderId = update.senderId,
                            text = update.text,
                            timestamp = update.timestamp,
                            direction = BaleChatItem.DIRECTION_INCOMING,
                            phoneNumber = resolved.phoneNumber,
                            senderName = resolved.senderName,
                        ),
                    )
                    if (rowId != -1L) {
                        added.add(update)
                    }
                }
                if (added.isNotEmpty()) {
                    notifyListeners()
                }
                added
            }
        }
    }

    fun appendOutgoing(
        chatId: String,
        messageId: String,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val contact = database.baleChatContactDao().get(chatId)
                database.baleMessageDao().insert(
                    BaleMessageEntity(
                        messageId = messageId,
                        chatId = chatId,
                        senderId = null,
                        text = text,
                        timestamp = timestamp,
                        direction = BaleChatItem.DIRECTION_OUTGOING,
                        phoneNumber = contact?.phoneNumber,
                        senderName = contact?.senderName,
                    ),
                )
                notifyListeners()
            }
        }
    }

    private fun resolveContact(
        update: BaleApi.BaleMessageUpdate,
        contactDao: com.example.smsgateway.data.BaleChatContactDao,
    ): ResolvedContact {
        val now = System.currentTimeMillis()
        val existing = contactDao.get(update.chatId)
        var phoneNumber = update.phoneNumber?.takeIf { it.isNotBlank() }
            ?: RubikaApi.extractPhoneFromText(update.text)
            ?: existing?.phoneNumber?.takeIf { it.isNotBlank() }
        var senderName = existing?.senderName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(update.contactFirstName, update.contactLastName)
                .joinToString(" ")
                .trim()
                .takeIf { it.isNotBlank() }
            ?: listOfNotNull(update.senderFirstName, update.senderLastName)
                .joinToString(" ")
                .trim()
                .takeIf { it.isNotBlank() }

        val normalizedNewPhone = phoneNumber?.let { PhoneUtils.formatForDisplay(it) }
        val normalizedExistingPhone = existing?.phoneNumber
            ?.takeIf { it.isNotBlank() }
            ?.let { PhoneUtils.formatForDisplay(it) }

        if (normalizedNewPhone != null && normalizedNewPhone != normalizedExistingPhone) {
            phoneNumber = normalizedNewPhone
            contactDao.upsert(
                BaleChatContactEntity(
                    chatId = update.chatId,
                    phoneNumber = phoneNumber,
                    senderName = senderName,
                    updatedAt = now,
                    lastPhoneRequestAt = existing?.lastPhoneRequestAt,
                ),
            )
            contactDao.backfillMessagePhones(update.chatId, phoneNumber)
        } else if (!senderName.isNullOrBlank() && senderName != existing?.senderName) {
            contactDao.upsert(
                BaleChatContactEntity(
                    chatId = update.chatId,
                    phoneNumber = phoneNumber ?: existing?.phoneNumber,
                    senderName = senderName,
                    updatedAt = now,
                    lastPhoneRequestAt = existing?.lastPhoneRequestAt,
                ),
            )
        }

        return ResolvedContact(
            phoneNumber = phoneNumber,
            senderName = senderName,
        )
    }

    private suspend fun syncOffsetBetweenStores() {
        withContext(Dispatchers.IO) {
            val settingsStore = SettingsStore.getInstance(appContext)
            val stateDao = database.baleStateDao()
            val roomOffset = stateDao.get()?.nextOffsetId?.takeIf { it.isNotBlank() }
            val prefOffset = settingsStore.getBaleNextOffsetId()
            when {
                roomOffset != null && prefOffset == null -> settingsStore.setBaleNextOffsetId(roomOffset)
                prefOffset != null && roomOffset == null -> stateDao.upsert(BaleStateEntity(nextOffsetId = prefOffset))
                prefOffset != null && roomOffset != null && prefOffset != roomOffset -> {
                    stateDao.upsert(BaleStateEntity(nextOffsetId = prefOffset))
                }
            }
        }
    }

    private fun notifyListeners() {
        val snapshot = listeners.toList()
        snapshot.forEach { listener ->
            runCatching { listener() }
        }
    }

    private fun BaleMessageEntity.toChatItem() = BaleChatItem(
        messageId = messageId,
        chatId = chatId,
        senderId = senderId,
        text = text,
        timestamp = timestamp,
        direction = direction,
        phoneNumber = phoneNumber,
        senderName = senderName,
    )

    private data class ResolvedContact(
        val phoneNumber: String?,
        val senderName: String?,
    )

    companion object {
        private const val DEFAULT_UI_LIMIT = 200

        @Volatile
        private var instance: BaleChatStore? = null

        fun getInstance(context: Context): BaleChatStore {
            return instance ?: synchronized(this) {
                instance ?: BaleChatStore(
                    context.applicationContext,
                    GatewayDatabase.getInstance(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
}
