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
    val groupId: Long? = null,
) {
    fun needsCompletion(): Boolean {
        return !phone.isNullOrBlank() && !registrationFieldsComplete
    }

    fun completionReminderSent(): Boolean = completionReminderSentAt != null

    fun hasMissingRegistrationFields(): Boolean {
        return city.isNullOrBlank() || km == null
    }
}
