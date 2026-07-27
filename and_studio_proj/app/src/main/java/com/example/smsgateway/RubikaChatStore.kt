package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.RubikaChatContactEntity
import com.example.smsgateway.data.RubikaMessageEntity
import com.example.smsgateway.data.RubikaStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class RubikaChatItem(
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

class RubikaChatStore private constructor(
    private val appContext: Context,
    private val database: GatewayDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableSetOf<() -> Unit>()
    private val api = RubikaApi.getInstance()
    private val enrichedChatIds = mutableSetOf<String>()

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

    fun getMessages(limit: Int = DEFAULT_UI_LIMIT): List<RubikaChatItem> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.rubikaMessageDao().getLatest(limit).map { it.toChatItem() }.asReversed()
            }
        }
    }

    fun getMessagesSince(since: Long, limit: Int): List<RubikaChatItem> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.rubikaMessageDao().getSince(since, limit).map { it.toChatItem() }
            }
        }
    }

    fun getContactPhone(chatId: String): String? {
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.rubikaChatContactDao().get(chatId)?.phoneNumber?.takeIf { it.isNotBlank() }
            }
        }
    }

    fun isPhonePaired(phone: String): Boolean {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        if (normalized.isBlank()) return false
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.rubikaChatContactDao().countByPhone(normalized) > 0
            }
        }
    }

    fun needsPhoneRequest(chatId: String): Boolean {
        return runBlocking {
            withContext(Dispatchers.IO) {
                val contact = database.rubikaChatContactDao().get(chatId)
                val phone = contact?.phoneNumber?.takeIf { it.isNotBlank() }
                phone == null && contact?.lastPhoneRequestAt == null
            }
        }
    }

    fun markPhoneRequestSent(chatId: String) {
        runBlocking {
            withContext(Dispatchers.IO) {
                val contactDao = database.rubikaChatContactDao()
                val existing = contactDao.get(chatId)
                val now = System.currentTimeMillis()
                contactDao.upsert(
                    RubikaChatContactEntity(
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
                val roomOffset = database.rubikaStateDao().get()?.nextOffsetId?.takeIf { it.isNotBlank() }
                val prefOffset = settingsStore.getRubikaNextOffsetId()
                roomOffset ?: prefOffset
            }
        }
    }

    fun setNextOffsetId(settingsStore: SettingsStore, offsetId: String?) {
        runBlocking {
            withContext(Dispatchers.IO) {
                val normalized = offsetId?.trim().orEmpty().takeIf { it.isNotBlank() }
                database.rubikaStateDao().upsert(
                    RubikaStateEntity(nextOffsetId = normalized),
                )
                settingsStore.setRubikaNextOffsetId(normalized)
            }
        }
    }

    fun appendUpdates(
        updates: List<RubikaApi.RubikaMessageUpdate>,
        token: String? = null,
    ): List<RubikaApi.RubikaMessageUpdate> {
        if (updates.isEmpty()) return emptyList()
        return runBlocking {
            withContext(Dispatchers.IO) {
                val messageDao = database.rubikaMessageDao()
                val contactDao = database.rubikaChatContactDao()
                val added = mutableListOf<RubikaApi.RubikaMessageUpdate>()
                updates.forEach { update ->
                    val resolved = resolveContact(update, contactDao, token)
                    val rowId = messageDao.insert(
                        RubikaMessageEntity(
                            messageId = update.messageId,
                            chatId = update.chatId,
                            senderId = update.senderId,
                            text = update.text,
                            timestamp = update.timestamp,
                            direction = RubikaChatItem.DIRECTION_INCOMING,
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
                val contact = database.rubikaChatContactDao().get(chatId)
                database.rubikaMessageDao().insert(
                    RubikaMessageEntity(
                        messageId = messageId,
                        chatId = chatId,
                        senderId = null,
                        text = text,
                        timestamp = timestamp,
                        direction = RubikaChatItem.DIRECTION_OUTGOING,
                        phoneNumber = contact?.phoneNumber,
                        senderName = contact?.senderName,
                    ),
                )
                notifyListeners()
            }
        }
    }

    private fun resolveContact(
        update: RubikaApi.RubikaMessageUpdate,
        contactDao: com.example.smsgateway.data.RubikaChatContactDao,
        token: String?,
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

        val normalizedNewPhone = phoneNumber?.let { PhoneUtils.formatForDisplay(it) }
        val normalizedExistingPhone = existing?.phoneNumber
            ?.takeIf { it.isNotBlank() }
            ?.let { PhoneUtils.formatForDisplay(it) }

        if (normalizedNewPhone != null && normalizedNewPhone != normalizedExistingPhone) {
            phoneNumber = normalizedNewPhone
            contactDao.upsert(
                RubikaChatContactEntity(
                    chatId = update.chatId,
                    phoneNumber = phoneNumber,
                    senderName = senderName,
                    updatedAt = now,
                    lastPhoneRequestAt = existing?.lastPhoneRequestAt,
                ),
            )
            contactDao.backfillMessagePhones(update.chatId, phoneNumber)
        }

        if (senderName.isNullOrBlank() && !token.isNullOrBlank() && update.chatId !in enrichedChatIds) {
            enrichedChatIds.add(update.chatId)
            runCatching {
                api.getChat(token, update.chatId)
            }.getOrNull()?.let { chatInfo ->
                senderName = listOfNotNull(chatInfo.firstName, chatInfo.lastName)
                    .joinToString(" ")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: chatInfo.title?.takeIf { it.isNotBlank() }
                    ?: chatInfo.username?.takeIf { it.isNotBlank() }
                if (!senderName.isNullOrBlank() || phoneNumber != null) {
                    contactDao.upsert(
                        RubikaChatContactEntity(
                            chatId = update.chatId,
                            phoneNumber = phoneNumber,
                            senderName = senderName,
                            updatedAt = now,
                            lastPhoneRequestAt = existing?.lastPhoneRequestAt,
                        ),
                    )
                }
            }
        }

        return ResolvedContact(
            phoneNumber = phoneNumber,
            senderName = senderName,
        )
    }

    private suspend fun syncOffsetBetweenStores() {
        withContext(Dispatchers.IO) {
            val settingsStore = SettingsStore.getInstance(appContext)
            val stateDao = database.rubikaStateDao()
            val roomOffset = stateDao.get()?.nextOffsetId?.takeIf { it.isNotBlank() }
            val prefOffset = settingsStore.getRubikaNextOffsetId()
            when {
                roomOffset != null && prefOffset == null -> settingsStore.setRubikaNextOffsetId(roomOffset)
                prefOffset != null && roomOffset == null -> stateDao.upsert(RubikaStateEntity(nextOffsetId = prefOffset))
                prefOffset != null && roomOffset != null && prefOffset != roomOffset -> {
                    stateDao.upsert(RubikaStateEntity(nextOffsetId = prefOffset))
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

    private fun RubikaMessageEntity.toChatItem() = RubikaChatItem(
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
        private var instance: RubikaChatStore? = null

        fun getInstance(context: Context): RubikaChatStore {
            return instance ?: synchronized(this) {
                instance ?: RubikaChatStore(
                    context.applicationContext,
                    GatewayDatabase.getInstance(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
}
