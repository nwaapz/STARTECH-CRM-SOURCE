package com.example.smsgateway.data



import androidx.room.Entity

import androidx.room.Index

import androidx.room.PrimaryKey



object ClubAutoMessageChannel {

    const val SMS = "sms"

    const val RUBIKA = "rubika"

    const val TELEGRAM = "telegram"

    const val BALE = "bale"

}



object ClubAutoMessageRunStatus {

    const val IDLE = "idle"

    const val RUNNING = "running"

}



/** Rules auto-created from serial-group warranty reminder toggles. */

object ClubAutoMessageAutoSource {

    const val WARRANTY_END = "warranty_end"

    const val WARRANTY_PRE = "warranty_pre"

}



@Entity(

    tableName = "club_auto_message_rules",

    indices = [

        Index(value = ["enabled"]),

        Index(value = ["createdAt"]),

        Index(value = ["linkedSerialGroupId", "autoSource"]),

    ],

)

data class ClubAutoMessageRuleEntity(

    @PrimaryKey(autoGenerate = true)

    val id: Long = 0,

    val title: String,

    val message: String,

    val offsetMonths: Int = 0,

    val offsetDays: Int = 0,

    val sendHour: Int = 10,

    val sendMinute: Int = 0,

    /** Seconds between each outbound message (SMS/bot), same idea as mass SMS. */

    val delaySeconds: Int = 10,

    /** Max distinct serials to alarm per Jalali day (1–10000). */

    val dailyCap: Int = 200,

    val sendSms: Boolean = true,

    val sendRubika: Boolean = false,

    val sendTelegram: Boolean = false,

    val sendBale: Boolean = false,

    /** end_user_client | seller_to_end_user — used when serialGroupIdsJson is empty */

    val serialCategory: String = "end_user_client",

    /** Comma-separated serial group ids; when set, only these groups are targeted */

    val serialGroupIdsJson: String? = null,

    /**

     * warranty_end | warranty_pre when created from a serial-group card toggle;

     * null for manually created club rules.

     */

    val autoSource: String? = null,

    /** Serial group that owns this auto rule (for create/delete from group card). */

    val linkedSerialGroupId: Long? = null,

    val enabled: Boolean = true,

    val createdAt: Long,

    val updatedAt: Long,

    val lastRunAt: Long? = null,

    val lastRunDateJalali: String? = null,

    val lastSuccessCount: Int = 0,

    val lastFailCount: Int = 0,

    /** idle | running — mid-run status for CRM progress. */

    val runStatus: String = ClubAutoMessageRunStatus.IDLE,

    val runStartedAt: Long? = null,

    /** Eligible serials for the current/last run batch. */

    val runTotalCount: Int = 0,

    /** Serials fully processed in the current/last run. */

    val runProcessedCount: Int = 0,

    /** Channel-level successes in the current/last run. */

    val runSentCount: Int = 0,

    /** Channel-level failures in the current/last run. */

    val runFailCount: Int = 0,

)



/**

 * Permanent send ledger: each serial receives a given rule on a given channel at most once.

 * [phone] is kept for audit/display; uniqueness is (ruleId, serial, channel).

 */

@Entity(

    tableName = "club_auto_message_sends",

    indices = [

        Index(value = ["ruleId", "serial", "channel"], unique = true),

        Index(value = ["ruleId", "sentAt"]),

        Index(value = ["ruleId", "sentDateJalali"]),

    ],

)

data class ClubAutoMessageSendEntity(

    @PrimaryKey(autoGenerate = true)

    val id: Long = 0,

    val ruleId: Long,

    val phone: String,

    val serial: String,

    val channel: String,

    val sentAt: Long,

    val sentDateJalali: String,

)


