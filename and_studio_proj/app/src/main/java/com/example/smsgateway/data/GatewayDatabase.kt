package com.example.smsgateway.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smsgateway.SerialRegistrationErrorReason

@Database(
    entities = [
        SerialEntity::class,
        SerialGroupEntity::class,
        SyncEventEntity::class,
        SmsSessionEntity::class,
        CommunicationMessageEntity::class,
        SerialRegistrationErrorEntity::class,
        MassSmsCampaignEntity::class,
        MassSmsRecipientEntity::class,
        RubikaMessageEntity::class,
        RubikaStateEntity::class,
        RubikaChatContactEntity::class,
        TelegramMessageEntity::class,
        TelegramStateEntity::class,
        TelegramChatContactEntity::class,
    ],
    version = 16,
    exportSchema = false,
)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun serialDao(): SerialDao

    abstract fun serialGroupDao(): SerialGroupDao

    abstract fun syncEventDao(): SyncEventDao

    abstract fun smsSessionDao(): SmsSessionDao

    abstract fun communicationMessageDao(): CommunicationMessageDao

    abstract fun serialRegistrationErrorDao(): SerialRegistrationErrorDao

    abstract fun massSmsCampaignDao(): MassSmsCampaignDao

    abstract fun rubikaMessageDao(): RubikaMessageDao

    abstract fun rubikaStateDao(): RubikaStateDao

    abstract fun rubikaChatContactDao(): RubikaChatContactDao

    abstract fun telegramMessageDao(): TelegramMessageDao

    abstract fun telegramStateDao(): TelegramStateDao

    abstract fun telegramChatContactDao(): TelegramChatContactDao

    companion object {
        private const val DB_NAME = "gateway_db.sqlite"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_events_at ON sync_events(at)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sms_sessions (
                        phone TEXT NOT NULL PRIMARY KEY,
                        serial TEXT NOT NULL,
                        nextField TEXT,
                        collectedCity TEXT,
                        collectedKm INTEGER,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE gateway_db ADD COLUMN completionReminderSentAt INTEGER",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE gateway_db ADD COLUMN registrationFieldsComplete INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE gateway_db ADD COLUMN registrationSubscriptionId INTEGER",
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS communication_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        phone TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        body TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        status TEXT,
                        simLabel TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_communication_messages_timestamp ON communication_messages(timestamp)",
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_communication_messages_phone_timestamp
                    ON communication_messages(phone, timestamp)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS serial_groups (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        prefix TEXT NOT NULL,
                        startNumber INTEGER NOT NULL,
                        endNumber INTEGER NOT NULL,
                        padWidth INTEGER NOT NULL,
                        description TEXT,
                        score INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE gateway_db ADD COLUMN groupId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gateway_db_groupId ON gateway_db(groupId)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS serial_registration_errors (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        phone TEXT NOT NULL,
                        serial TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_serial_registration_errors_phone_at
                    ON serial_registration_errors(phone, at)
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_serial_registration_errors_at ON serial_registration_errors(at)",
                )
                backfillFromCommunications(db)
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mass_sms_campaigns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        message TEXT NOT NULL,
                        delaySeconds INTEGER NOT NULL,
                        audienceType TEXT NOT NULL,
                        provinceId TEXT,
                        cityName TEXT,
                        status TEXT NOT NULL,
                        totalCount INTEGER NOT NULL,
                        sentCount INTEGER NOT NULL,
                        failedCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        startedAt INTEGER,
                        completedAt INTEGER,
                        queueOrder INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_mass_sms_campaigns_status_queueOrder
                    ON mass_sms_campaigns(status, queueOrder)
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mass_sms_campaigns_createdAt ON mass_sms_campaigns(createdAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mass_sms_recipients (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        campaignId INTEGER NOT NULL,
                        phone TEXT NOT NULL,
                        status TEXT NOT NULL,
                        sentAt INTEGER,
                        errorMessage TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_mass_sms_recipients_campaignId_status
                    ON mass_sms_recipients(campaignId, status)
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mass_sms_recipients_campaignId ON mass_sms_recipients(campaignId)",
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rubika_messages (
                        messageId TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        senderId TEXT,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        direction TEXT NOT NULL,
                        phoneNumber TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_rubika_messages_timestamp ON rubika_messages(timestamp)",
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_rubika_messages_chatId_timestamp
                    ON rubika_messages(chatId, timestamp)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS telegram_messages (
                        messageId TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        senderId TEXT,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        direction TEXT NOT NULL,
                        phoneNumber TEXT,
                        senderName TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_telegram_messages_timestamp ON telegram_messages(timestamp)",
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_telegram_messages_chatId_timestamp
                    ON telegram_messages(chatId, timestamp)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS telegram_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        nextOffsetId TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO telegram_state (id, nextOffsetId)
                    VALUES (1, NULL)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS telegram_chats (
                        chatId TEXT NOT NULL PRIMARY KEY,
                        phoneNumber TEXT,
                        senderName TEXT,
                        updatedAt INTEGER NOT NULL,
                        lastPhoneRequestAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_telegram_chats_phoneNumber ON telegram_chats(phoneNumber)",
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE mass_sms_campaigns ADD COLUMN serialGroupIdsJson TEXT",
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE mass_sms_campaigns ADD COLUMN registrationFromDate TEXT",
                )
                db.execSQL(
                    "ALTER TABLE mass_sms_campaigns ADD COLUMN registrationToDate TEXT",
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE rubika_chats ADD COLUMN lastPhoneRequestAt INTEGER",
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rubika_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        nextOffsetId TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO rubika_state (id, nextOffsetId)
                    VALUES (1, NULL)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rubika_chats (
                        chatId TEXT NOT NULL PRIMARY KEY,
                        phoneNumber TEXT,
                        senderName TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_rubika_chats_phoneNumber ON rubika_chats(phoneNumber)",
                )
                db.execSQL(
                    "ALTER TABLE rubika_messages ADD COLUMN senderName TEXT",
                )
            }
        }

        private fun backfillFromCommunications(db: SupportSQLiteDatabase) {
            val invalidReply = "شماره سریال معتبر نیست."
            val cursor = db.query(
                """
                SELECT i.phone, i.body, i.timestamp
                FROM communication_messages i
                INNER JOIN communication_messages o
                    ON o.phone = i.phone AND o.timestamp = i.timestamp + 1
                WHERE i.direction = 'incoming'
                  AND o.direction = 'outgoing'
                  AND o.body = ?
                """.trimIndent(),
                arrayOf(invalidReply),
            )
            cursor.use {
                while (it.moveToNext()) {
                    val phone = it.getString(0)
                    val body = it.getString(1)
                    val timestamp = it.getLong(2)
                    val serial = extractSerialCandidate(body) ?: continue
                    val reason = classifySerialReason(db, serial)
                    db.execSQL(
                        """
                        INSERT INTO serial_registration_errors (phone, serial, reason, at)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(phone, serial, reason, timestamp),
                    )
                }
            }
        }

        private fun extractSerialCandidate(body: String): String? {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return null
            val token = trimmed.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
            val serial = token.ifEmpty { trimmed }.uppercase()
            return serial.takeIf { it.isNotEmpty() }
        }

        private fun classifySerialReason(db: SupportSQLiteDatabase, serial: String): String {
            val cursor = db.query(
                """
                SELECT phone FROM gateway_db
                WHERE UPPER(serial) = UPPER(?)
                LIMIT 1
                """.trimIndent(),
                arrayOf(serial),
            )
            cursor.use {
                if (!it.moveToNext()) {
                    return SerialRegistrationErrorReason.NOT_FOUND
                }
                val phone = it.getString(0)
                return if (!phone.isNullOrBlank()) {
                    SerialRegistrationErrorReason.ALREADY_USED
                } else {
                    SerialRegistrationErrorReason.NOT_FOUND
                }
            }
        }

        @Volatile
        private var instance: GatewayDatabase? = null

        fun getInstance(context: Context): GatewayDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GatewayDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                    )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
