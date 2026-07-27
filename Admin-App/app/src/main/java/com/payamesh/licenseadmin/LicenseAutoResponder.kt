package com.payamesh.licenseadmin

import android.content.Context
import android.util.Log
import com.payamesh.licenseadmin.data.LicenseAdminDatabase
import com.payamesh.licenseadmin.data.LicenseCheckLogEntity
import com.payamesh.licenseadmin.data.LicenseSeatEntity
import com.payamesh.licenseadmin.license.LicenseSmsProtocol
import kotlinx.coroutines.runBlocking

object LicenseAutoResponder {
    private const val TAG = "LicenseAutoResponder"

    fun handleCheck(
        context: Context,
        sender: String,
        body: String,
        subscriptionId: Int,
    ): Boolean {
        if (!LicenseSmsProtocol.isCheckMessage(body)) return false
        val check = LicenseSmsProtocol.parseCheck(body) ?: return true
        val msisdn = LicenseSmsProtocol.normalizeMsisdn(sender)
        if (msisdn == null) {
            Log.w(TAG, "CHK from unparseable sender=$sender")
            return true
        }

        runBlocking {
            val dao = LicenseAdminDatabase.getInstance(context).licenseSeatDao()
            val seat = dao.findByMsisdn(msisdn)
            val now = System.currentTimeMillis()
            val reply: String
            val result: String
            when {
                seat == null -> {
                    reply = LicenseSmsProtocol.buildDeny(
                        nonce = check.nonce,
                        reason = LicenseSmsProtocol.REASON_UNREGISTERED,
                    )
                    result = "deny_unregistered"
                }
                seat.revoked -> {
                    reply = LicenseSmsProtocol.buildDeny(
                        nonce = check.nonce,
                        reason = LicenseSmsProtocol.REASON_REVOKED,
                    )
                    result = "deny_revoked"
                }
                else -> {
                    reply = LicenseSmsProtocol.buildAllow(
                        nonce = check.nonce,
                        msisdnNorm = msisdn,
                        issuedAtEpochMs = now,
                    )
                    result = "allow"
                }
            }
            dao.insertLog(
                LicenseCheckLogEntity(
                    senderMsisdn = msisdn,
                    nonce = check.nonce,
                    result = result,
                    at = now,
                    deviceId = check.deviceId,
                    simFingerprint = check.simFingerprint,
                ),
            )
            try {
                SmsOutbound.send(
                    context,
                    subscriptionId,
                    PhoneUtils.normalizeForSmsSend(sender),
                    reply,
                )
                Log.i(TAG, "Auto-reply $result to $msisdn")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to send auto-reply", error)
            }
        }
        return true
    }

    suspend fun addSeat(context: Context, rawPhone: String, note: String?): LicenseSeatEntity {
        val msisdn = LicenseSmsProtocol.normalizeMsisdn(rawPhone)
            ?: throw IllegalArgumentException("شماره موبایل نامعتبر است")
        val dao = LicenseAdminDatabase.getInstance(context).licenseSeatDao()
        val existing = dao.findByMsisdn(msisdn)
        val now = System.currentTimeMillis()
        if (existing != null) {
            val updated = existing.copy(
                revoked = false,
                note = note ?: existing.note,
                updatedAt = now,
            )
            dao.update(updated)
            return updated
        }
        val entity = LicenseSeatEntity(
            msisdn = msisdn,
            note = note,
            createdAt = now,
            updatedAt = now,
            revoked = false,
        )
        val id = dao.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun revokeSeat(context: Context, msisdnRaw: String) {
        val msisdn = LicenseSmsProtocol.normalizeMsisdn(msisdnRaw)
            ?: throw IllegalArgumentException("شماره موبایل نامعتبر است")
        val dao = LicenseAdminDatabase.getInstance(context).licenseSeatDao()
        val existing = dao.findByMsisdn(msisdn)
            ?: throw IllegalArgumentException("صندلی یافت نشد")
        dao.update(existing.copy(revoked = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun transferSeat(context: Context, fromRaw: String, toRaw: String, note: String?) {
        val from = LicenseSmsProtocol.normalizeMsisdn(fromRaw)
            ?: throw IllegalArgumentException("شماره مبدأ نامعتبر است")
        val to = LicenseSmsProtocol.normalizeMsisdn(toRaw)
            ?: throw IllegalArgumentException("شماره مقصد نامعتبر است")
        if (from == to) throw IllegalArgumentException("شماره‌ها یکسان هستند")
        val dao = LicenseAdminDatabase.getInstance(context).licenseSeatDao()
        val source = dao.findByMsisdn(from)
            ?: throw IllegalArgumentException("صندلی مبدأ یافت نشد")
        if (source.revoked) throw IllegalArgumentException("صندلی مبدأ باطل شده است")
        val now = System.currentTimeMillis()
        dao.update(source.copy(revoked = true, updatedAt = now))
        val dest = dao.findByMsisdn(to)
        if (dest != null) {
            dao.update(
                dest.copy(
                    revoked = false,
                    previousMsisdn = from,
                    note = note ?: dest.note,
                    updatedAt = now,
                ),
            )
        } else {
            dao.insert(
                LicenseSeatEntity(
                    msisdn = to,
                    note = note ?: source.note,
                    createdAt = now,
                    updatedAt = now,
                    revoked = false,
                    previousMsisdn = from,
                ),
            )
        }
    }
}
