package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "gateway_db",
    indices = [
        Index(value = ["serial"], unique = true),
        Index(value = ["phone"]),
        Index(value = ["groupId"]),
    ],
)
data class SerialEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serial: String,
    val phone: String? = null,
    val date: String? = null,
    val city: String? = null,
    val km: Int? = null,
    val completionReminderSentAt: Long? = null,
    val registrationFieldsComplete: Boolean = true,
    val registrationSubscriptionId: Int? = null,
    /** sms | rubika | telegram — channel that performed registration */
    val registrationSource: String? = null,
    val groupId: Long? = null,
    /** Last-write-wins timestamp for cloud hybrid sync (epoch ms). */
    val syncUpdatedMs: Long = 0,
) {
    fun needsCompletion(): Boolean {
        // Sentinel "0" is unused inventory (same as remote CRM), not a partial registration.
        val registered = !phone.isNullOrBlank() && phone.trim() != "0"
        return registered && !registrationFieldsComplete
    }

    fun completionReminderSent(): Boolean = completionReminderSentAt != null

    /** @deprecated Prefer RegistrationFieldRules.missingFields with category + knownCity. */
    fun hasMissingRegistrationFields(): Boolean {
        return city.isNullOrBlank() || km == null
    }
}
