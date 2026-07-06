package com.example.smsgateway

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.smsgateway.lan.LanTraceLog

object DeviceContactHelper {
    fun phonesMatch(first: String?, second: String?): Boolean {
        val normalizedFirst = first?.let { PhoneUtils.normalizeForSmsSend(it) }.orEmpty()
        val normalizedSecond = second?.let { PhoneUtils.normalizeForSmsSend(it) }.orEmpty()
        if (normalizedFirst.isEmpty() || normalizedSecond.isEmpty()) return false
        return normalizedFirst == normalizedSecond
    }

    fun ensureContact(context: Context, phone: String, clientId: Long) {
        if (!hasContactsPermission(context)) return

        val normalizedPhone = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        if (normalizedPhone.isEmpty()) return

        try {
            if (isPhoneInContacts(context, normalizedPhone)) return
            insertContact(context, normalizedPhone, clientId)
        } catch (error: Exception) {
            LanTraceLog.e(
                "DeviceContactHelper",
                "Failed to ensure contact for $normalizedPhone (client$clientId)",
                error,
            )
        }
    }

    private fun hasContactsPermission(context: Context): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    private fun isPhoneInContacts(context: Context, phone: String): Boolean {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (numberIndex < 0) return false
            while (cursor.moveToNext()) {
                val storedNumber = cursor.getString(numberIndex)
                if (phonesMatch(storedNumber, phone)) return true
            }
        }
        return false
    }

    private fun insertContact(context: Context, phone: String, clientId: Long) {
        val displayName = "client$clientId"
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                )
                .build(),
        )
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
    }
}
