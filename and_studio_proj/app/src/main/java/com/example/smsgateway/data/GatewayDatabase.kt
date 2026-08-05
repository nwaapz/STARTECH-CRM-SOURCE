package com.example.smsgateway.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smsgateway.PhoneUtils
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
        WhatsAppCampaignEntity::class,
        WhatsAppRecipientEntity::class,
        RubikaMessageEntity::class,
        RubikaStateEntity::class,
        RubikaChatContactEntity::class,
        TelegramMessageEntity::class,
        TelegramStateEntity::class,
        TelegramChatContactEntity::class,
        BaleMessageEntity::class,
        BaleStateEntity::class,
        BaleChatContactEntity::class,
        ClubAutoMessageRuleEntity::class,
        ClubAutoMessageSendEntity::class,
        SerialPhotoSubmissionEntity::class,
        BotChatSessionEntity::class,
        BotMediaAssetEntity::class,
        CustomerReferralEntity::class,
        CustomerScoreEventEntity::class,
        ReferralPendingEntity::class,
        SmsSendQuotaEventEntity::class,
        CrmUserEntity::class,
        CrmSessionEntity::class,
        LotteryWinnerEntity::class,
        LotterySuspendedPhoneEntity::class,
        SyncOutboxEntity::class,
    ],
    version = 43,
    exportSchema = false,
)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun serialDao(): SerialDao

    abstract fun serialGroupDao(): SerialGroupDao

    abstract fun syncEventDao(): SyncEventDao

    abstract fun smsSessionDao(): SmsSessionDao

    abstract fun smsSendQuotaEventDao(): SmsSendQuotaEventDao

    abstract fun communicationMessageDao(): CommunicationMessageDao

    abstract fun serialRegistrationErrorDao(): SerialRegistrationErrorDao

    abstract fun massSmsCampaignDao(): MassSmsCampaignDao

    abstract fun whatsAppCampaignDao(): WhatsAppCampaignDao

    abstract fun rubikaMessageDao(): RubikaMessageDao

    abstract fun rubikaStateDao(): RubikaStateDao

    abstract fun rubikaChatContactDao(): RubikaChatContactDao

    abstract fun telegramMessageDao(): TelegramMessageDao

    abstract fun telegramStateDao(): TelegramStateDao

    abstract fun telegramChatContactDao(): TelegramChatContactDao

    abstract fun baleMessageDao(): BaleMessageDao

    abstract fun baleStateDao(): BaleStateDao

    abstract fun baleChatContactDao(): BaleChatContactDao

    abstract fun clubAutoMessageDao(): ClubAutoMessageDao

    abstract fun serialPhotoSubmissionDao(): SerialPhotoSubmissionDao

    abstract fun botChatSessionDao(): BotChatSessionDao

    abstract fun botMediaAssetDao(): BotMediaAssetDao

    abstract fun customerReferralDao(): CustomerReferralDao

    abstract fun customerScoreEventDao(): CustomerScoreEventDao

    abstract fun referralPendingDao(): ReferralPendingDao

    abstract fun crmUserDao(): CrmUserDao

    abstract fun crmSessionDao(): CrmSessionDao

    abstract fun lotteryWinnerDao(): LotteryWinnerDao

    abstract fun lotterySuspendedPhoneDao(): LotterySuspendedPhoneDao

    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        const val DB_NAME = "gateway_db.sqlite"

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

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE serial_groups ADD COLUMN category TEXT NOT NULL DEFAULT 'end_user_client'",
                )
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE serial_groups ADD COLUMN generationMode TEXT NOT NULL DEFAULT 'range'",
                )
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE gateway_db ADD COLUMN registrationSource TEXT",
                )
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS club_auto_message_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        message TEXT NOT NULL,
                        offsetMonths INTEGER NOT NULL,
                        offsetDays INTEGER NOT NULL,
                        sendHour INTEGER NOT NULL,
                        sendMinute INTEGER NOT NULL,
                        sendSms INTEGER NOT NULL,
                        sendRubika INTEGER NOT NULL,
                        sendTelegram INTEGER NOT NULL,
                        serialCategory TEXT NOT NULL DEFAULT 'end_user_client',
                        serialGroupIdsJson TEXT,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastRunAt INTEGER,
                        lastRunDateJalali TEXT,
                        lastSuccessCount INTEGER NOT NULL,
                        lastFailCount INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_club_auto_message_rules_enabled ON club_auto_message_rules(enabled)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_club_auto_message_rules_createdAt ON club_auto_message_rules(createdAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS club_auto_message_sends (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ruleId INTEGER NOT NULL,
                        phone TEXT NOT NULL,
                        channel TEXT NOT NULL,
                        sentAt INTEGER NOT NULL,
                        sentDateJalali TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_club_auto_message_sends_ruleId_phone_channel_sentDateJalali
                    ON club_auto_message_sends(ruleId, phone, channel, sentDateJalali)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_club_auto_message_sends_ruleId_sentDateJalali
                    ON club_auto_message_sends(ruleId, sentDateJalali)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE serial_groups ADD COLUMN warrantyMonths INTEGER")
                db.execSQL("ALTER TABLE serial_groups ADD COLUMN warrantyKm INTEGER")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE club_auto_message_rules
                    ADD COLUMN serialCategory TEXT NOT NULL DEFAULT 'end_user_client'
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN serialGroupIdsJson TEXT",
                )
            }
        }

        /** Permanent unique send ledger: one row per (rule, phone, channel). */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS club_auto_message_sends_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ruleId INTEGER NOT NULL,
                        phone TEXT NOT NULL,
                        channel TEXT NOT NULL,
                        sentAt INTEGER NOT NULL,
                        sentDateJalali TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO club_auto_message_sends_new (ruleId, phone, channel, sentAt, sentDateJalali)
                    SELECT ruleId, phone, channel, MIN(sentAt), MIN(sentDateJalali)
                    FROM club_auto_message_sends
                    GROUP BY ruleId, phone, channel
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE club_auto_message_sends")
                db.execSQL("ALTER TABLE club_auto_message_sends_new RENAME TO club_auto_message_sends")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_club_auto_message_sends_ruleId_phone_channel
                    ON club_auto_message_sends(ruleId, phone, channel)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_club_auto_message_sends_ruleId_sentAt
                    ON club_auto_message_sends(ruleId, sentAt)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE serial_groups
                    ADD COLUMN imageVerificationRequired INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS serial_photo_submissions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        serial TEXT NOT NULL,
                        serialRowId INTEGER,
                        groupId INTEGER,
                        phone TEXT NOT NULL,
                        channel TEXT NOT NULL,
                        chatId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        mimeType TEXT,
                        remoteFileId TEXT,
                        scoreAwarded INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        reviewedAt INTEGER,
                        reviewNote TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_serial_photo_submissions_status_createdAt ON serial_photo_submissions(status, createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_serial_photo_submissions_phone ON serial_photo_submissions(phone)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_serial_photo_submissions_serial ON serial_photo_submissions(serial)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_serial_photo_submissions_groupId ON serial_photo_submissions(groupId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bot_chat_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        channel TEXT NOT NULL,
                        chatId TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        state TEXT NOT NULL,
                        pendingSerial TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_bot_chat_sessions_channel_chatId
                    ON bot_chat_sessions(channel, chatId)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bale_messages (
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
                    "CREATE INDEX IF NOT EXISTS index_bale_messages_timestamp ON bale_messages(timestamp)",
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_bale_messages_chatId_timestamp
                    ON bale_messages(chatId, timestamp)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bale_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        nextOffsetId TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO bale_state (id, nextOffsetId)
                    VALUES (1, NULL)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bale_chats (
                        chatId TEXT NOT NULL PRIMARY KEY,
                        phoneNumber TEXT,
                        senderName TEXT,
                        updatedAt INTEGER NOT NULL,
                        lastPhoneRequestAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bale_chats_phoneNumber ON bale_chats(phoneNumber)",
                )
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN sendBale INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bot_media_assets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fileName TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        rubikaFileId TEXT,
                        telegramFileId TEXT,
                        baleFileId TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "ALTER TABLE mass_sms_campaigns ADD COLUMN channel TEXT NOT NULL DEFAULT 'sms'",
                )
                db.execSQL(
                    "ALTER TABLE mass_sms_campaigns ADD COLUMN mediaAssetId INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE mass_sms_campaigns ADD COLUMN skippedCount INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds an explicit `channel` column to communication_messages so each message can show
         * which tunnel it travelled through (sms / rubika / telegram / bale). Existing bot rows
         * are backfilled from the Persian simLabel the bot processors historically stamped.
         */
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE communication_messages ADD COLUMN channel TEXT NOT NULL DEFAULT 'sms'",
                )
                db.execSQL("UPDATE communication_messages SET channel = 'rubika' WHERE simLabel = 'روبیکا'")
                db.execSQL("UPDATE communication_messages SET channel = 'telegram' WHERE simLabel = 'تلگرام'")
                db.execSQL("UPDATE communication_messages SET channel = 'bale' WHERE simLabel = 'بله'")
            }
        }

        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS customer_referrals (
                        phone TEXT NOT NULL PRIMARY KEY,
                        referralCode TEXT NOT NULL,
                        referredBy TEXT,
                        referredByCode TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_customer_referrals_referralCode ON customer_referrals(referralCode)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_customer_referrals_referredBy ON customer_referrals(referredBy)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS customer_score_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        phone TEXT NOT NULL,
                        points INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        level INTEGER NOT NULL,
                        sourcePhone TEXT,
                        sourceType TEXT NOT NULL,
                        sourceId INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_customer_score_events_phone ON customer_score_events(phone)",
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_customer_score_events_phone_sourceType_sourceId_level
                    ON customer_score_events(phone, sourceType, sourceId, level)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS referral_pending (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        channel TEXT NOT NULL,
                        chatId TEXT NOT NULL,
                        code TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_referral_pending_channel_chatId
                    ON referral_pending(channel, chatId)
                    """.trimIndent(),
                )
            }
        }

        /**
         * Convert Persian/Arabic-Indic digits in registration dates to Latin ASCII so
         * lexicographic Jalali date filters (club auto-messages, mass SMS, etc.) work.
         * Root cause: JalaliHelper used String.format under fa_IR locale.
         */
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE gateway_db
                    SET date = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
                        REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
                        date,
                        '۰','0'),'۱','1'),'۲','2'),'۳','3'),'۴','4'),'۵','5'),'۶','6'),'۷','7'),'۸','8'),'۹','9'),
                        '٠','0'),'١','1'),'٢','2'),'٣','3'),'٤','4'),'٥','5'),'٦','6'),'٧','7'),'٨','8'),'٩','9')
                    WHERE date IS NOT NULL AND date != ''
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN delaySeconds INTEGER NOT NULL DEFAULT 10",
                )
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN runStatus TEXT NOT NULL DEFAULT 'idle'",
                )
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN runStartedAt INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN runTotalCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN runProcessedCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN runSentCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN runFailCount INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS license_seats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        msisdn TEXT NOT NULL,
                        note TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        revoked INTEGER NOT NULL,
                        previousMsisdn TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_license_seats_msisdn ON license_seats(msisdn)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS license_check_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        senderMsisdn TEXT NOT NULL,
                        nonce TEXT NOT NULL,
                        result TEXT NOT NULL,
                        at INTEGER NOT NULL,
                        deviceId TEXT,
                        simFingerprint TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS license_check_logs")
                db.execSQL("DROP TABLE IF EXISTS license_seats")
            }
        }

        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sms_sessions ADD COLUMN subscriptionId INTEGER",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sms_send_quota_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        subscriptionKey TEXT NOT NULL,
                        sentAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_sms_send_quota_events_subscriptionKey_sentAt
                    ON sms_send_quota_events(subscriptionKey, sentAt)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_sms_send_quota_events_sentAt
                    ON sms_send_quota_events(sentAt)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS crm_users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        username TEXT NOT NULL,
                        passwordHash TEXT NOT NULL,
                        role INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        syncUpdatedMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_crm_users_username ON crm_users(username)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS crm_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        userId INTEGER NOT NULL,
                        token TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_crm_sessions_token ON crm_sessions(token)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lottery_winners (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        phone TEXT NOT NULL,
                        city TEXT,
                        scoreAtWin INTEGER NOT NULL,
                        dateJalali TEXT NOT NULL,
                        wonAt INTEGER NOT NULL,
                        syncUpdatedMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lottery_winners_wonAt ON lottery_winners(wonAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lottery_suspended_phones (
                        phone TEXT NOT NULL PRIMARY KEY,
                        reason TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        lastError TEXT,
                        lastAttemptAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_eventId ON sync_outbox(eventId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_outbox_createdAt ON sync_outbox(createdAt)",
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_sync_outbox_status_createdAt
                    ON sync_outbox(status, createdAt)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN autoSource TEXT",
                )
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN linkedSerialGroupId INTEGER",
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_club_auto_message_rules_linkedSerialGroupId_autoSource
                    ON club_auto_message_rules(linkedSerialGroupId, autoSource)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE gateway_db ADD COLUMN syncUpdatedMs INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE serial_groups ADD COLUMN syncUpdatedMs INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE serial_groups ADD COLUMN syncOriginGroupId INTEGER",
                )
                db.execSQL(
                    "UPDATE serial_groups SET syncUpdatedMs = createdAt WHERE syncUpdatedMs = 0",
                )
            }
        }

        /** Remote CRM treats phone sentinel "0" as unused inventory — normalize local rows. */
        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE gateway_db
                    SET phone = NULL,
                        registrationFieldsComplete = 1
                    WHERE phone IS NOT NULL
                      AND TRIM(phone) = '0'
                    """.trimIndent(),
                )
            }
        }

        /**
         * CSV/phpMyAdmin dumps often write the literal text NULL for empty phones.
         * Those were incorrectly counted as registered — clear them.
         */
        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE gateway_db
                    SET phone = NULL,
                        date = NULL,
                        registrationFieldsComplete = 1
                    WHERE phone IS NOT NULL
                      AND (
                        UPPER(TRIM(phone)) = 'NULL'
                        OR UPPER(TRIM(phone)) = 'NIL'
                        OR TRIM(phone) = '\N'
                        OR UPPER(TRIM(phone)) = 'N/A'
                      )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE gateway_db
                    SET city = NULL
                    WHERE city IS NOT NULL
                      AND (
                        UPPER(TRIM(city)) = 'NULL'
                        OR UPPER(TRIM(city)) = 'NIL'
                        OR TRIM(city) = '\N'
                      )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Anything that is not a digit-bearing phone-like value is unused inventory.
         * Clears leftover CSV junk (letters-only, too-short, dashes, etc.).
         */
        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE gateway_db
                    SET phone = NULL,
                        date = NULL,
                        registrationFieldsComplete = 1
                    WHERE phone IS NOT NULL
                      AND (
                        TRIM(phone) = ''
                        OR TRIM(phone) = '0'
                        OR TRIM(phone) = '-'
                        OR TRIM(phone) = '.'
                        OR UPPER(TRIM(phone)) IN ('NULL', 'NIL', 'N/A')
                        OR TRIM(phone) = '\N'
                        OR phone GLOB '*[A-Za-z]*'
                        OR length(
                          replace(replace(replace(replace(replace(replace(
                            TRIM(phone), '+', ''), ' ', ''), '-', ''), '(', ''), ')', ''), '.', '')
                        ) < 10
                      )
                    """.trimIndent(),
                )
            }
        }

        /** Dual-buffer sync: coalesce key on outbox for one-pending-per-serial/group. */
        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN coalesceKey TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_outbox_status_coalesceKey " +
                        "ON sync_outbox(status, coalesceKey)",
                )
            }
        }

        private val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE club_auto_message_rules ADD COLUMN dailyCap INTEGER NOT NULL DEFAULT 200",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS club_auto_message_sends_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ruleId INTEGER NOT NULL,
                        phone TEXT NOT NULL,
                        serial TEXT NOT NULL,
                        channel TEXT NOT NULL,
                        sentAt INTEGER NOT NULL,
                        sentDateJalali TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO club_auto_message_sends_new
                        (id, ruleId, phone, serial, channel, sentAt, sentDateJalali)
                    SELECT id, ruleId, phone, '__phone__' || phone, channel, sentAt, sentDateJalali
                    FROM club_auto_message_sends
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE club_auto_message_sends")
                db.execSQL("ALTER TABLE club_auto_message_sends_new RENAME TO club_auto_message_sends")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_club_auto_message_sends_ruleId_serial_channel
                    ON club_auto_message_sends(ruleId, serial, channel)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_club_auto_message_sends_ruleId_sentAt
                    ON club_auto_message_sends(ruleId, sentAt)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_club_auto_message_sends_ruleId_sentDateJalali
                    ON club_auto_message_sends(ruleId, sentDateJalali)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS whatsapp_campaigns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        message TEXT NOT NULL,
                        audienceType TEXT NOT NULL,
                        provinceId TEXT,
                        cityName TEXT,
                        serialGroupIdsJson TEXT,
                        registrationFromDate TEXT,
                        registrationToDate TEXT,
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
                    CREATE INDEX IF NOT EXISTS index_whatsapp_campaigns_status_queueOrder
                    ON whatsapp_campaigns(status, queueOrder)
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_whatsapp_campaigns_createdAt ON whatsapp_campaigns(createdAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS whatsapp_recipients (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        campaignId INTEGER NOT NULL,
                        phone TEXT NOT NULL,
                        phoneIntl TEXT NOT NULL,
                        contactName TEXT,
                        status TEXT NOT NULL,
                        sentAt INTEGER,
                        errorMessage TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_whatsapp_recipients_campaignId_status
                    ON whatsapp_recipients(campaignId, status)
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_whatsapp_recipients_campaignId ON whatsapp_recipients(campaignId)",
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
                WHERE serial = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(serial),
            )
            cursor.use {
                if (!it.moveToNext()) {
                    return SerialRegistrationErrorReason.NOT_FOUND
                }
                val phone = it.getString(0)
                return if (PhoneUtils.isRegisteredPhone(phone)) {
                    SerialRegistrationErrorReason.ALREADY_USED
                } else {
                    SerialRegistrationErrorReason.NOT_FOUND
                }
            }
        }

        @Volatile
        private var instance: GatewayDatabase? = null

        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

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
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_31,
                        MIGRATION_31_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35,
                        MIGRATION_35_36,
                        MIGRATION_36_37,
                        MIGRATION_37_38,
                        MIGRATION_38_39,
                        MIGRATION_39_40,
                        MIGRATION_40_41,
                        MIGRATION_41_42,
                        MIGRATION_42_43,
                    )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
