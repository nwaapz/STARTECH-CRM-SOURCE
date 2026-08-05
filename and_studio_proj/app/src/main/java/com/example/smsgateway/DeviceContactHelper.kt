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

    /**
     * Resolve an inbound SMS address to an Iranian mobile for **device SIM** mode.
     *
     * 1. Prefer PDU fields that are already a valid mobile (`09…` / `+989…`).
     * 2. If Android only gave a contact name (or a non-mobile line), look up that
     *    contact and use its stored phone number — only if that number is a mobile.
     * 3. Otherwise return null (short codes, bank lines, unresolved names → ignore).
     */
    fun resolveDeviceSimMobileSender(
        context: Context,
        originatingAddress: String?,
        displayOriginatingAddress: String?,
    ): String? {
        val rawCandidates = listOf(originatingAddress, displayOriginatingAddress)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Direct hit: PDU already carries a real mobile.
        for (candidate in rawCandidates) {
            PhoneUtils.nullIfUnregistered(candidate)?.let { return it }
        }

        if (!hasReadContactsPermission(context)) {
            LanTraceLog.w(
                "DeviceContactHelper",
                "No READ_CONTACTS; cannot resolve contact name for sender=$rawCandidates",
            )
            return null
        }

        // Contact lookup: name or non-mobile address → actual phone(s) on that contact.
        val phonesFromContacts = LinkedHashSet<String>()
        for (candidate in rawCandidates) {
            phonesFromContacts += lookupPhonesForSmsAddress(context, candidate)
        }
        for (phone in phonesFromContacts) {
            PhoneUtils.nullIfUnregistered(phone)?.let { mobile ->
                LanTraceLog.i(
                    "DeviceContactHelper",
                    "Resolved contact SMS address to mobile=$mobile (raw=$rawCandidates)",
                )
                return mobile
            }
        }

        LanTraceLog.w(
            "DeviceContactHelper",
            "Ignoring non-mobile SMS sender raw=$rawCandidates contactPhones=$phonesFromContacts",
        )
        return null
    }

    private fun hasReadContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasContactsPermission(context: Context): Boolean {
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        return hasReadContactsPermission(context) && writeGranted
    }

    /**
     * Find phone numbers stored on contacts matching [address] either as a phone
     * number or as a display name.
     */
    private fun lookupPhonesForSmsAddress(context: Context, address: String): List<String> {
        val found = LinkedHashSet<String>()
        try {
            // Match by phone number (short code or MSISDN saved on a contact).
            if (address.any { it.isDigit() }) {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val numberIndex =
                        cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        while (cursor.moveToNext()) {
                            val stored = cursor.getString(numberIndex) ?: continue
                            // Exact MSISDN / short-code match only — never suffix match
                            // (that could map 20001390 → an unrelated 09… number).
                            if (phonesMatch(stored, address) || digitKeysEqual(stored, address)) {
                                found += stored
                            }
                        }
                    }
                }
            }

            // Match by display name (OEM put contact name in the SMS address field).
            val name = address.trim()
            if (name.isNotEmpty() && !PhoneUtils.isRegisteredPhone(name)) {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}=?",
                    arrayOf(name),
                    null,
                )?.use { cursor ->
                    val numberIndex =
                        cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        while (cursor.moveToNext()) {
                            cursor.getString(numberIndex)?.let { found += it }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            LanTraceLog.e("DeviceContactHelper", "Contact lookup failed for $address", error)
        }
        return found.toList()
    }

    private fun digitKeysEqual(a: String, b: String): Boolean {
        fun key(value: String): String {
            var d = value.filter { it.isDigit() }
            if (d.startsWith("0098")) d = d.substring(4)
            else if (d.startsWith("98") && d.length > 2) d = d.substring(2)
            return d.trimStart('0')
        }
        val ka = key(a)
        val kb = key(b)
        return ka.isNotEmpty() && ka == kb
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
