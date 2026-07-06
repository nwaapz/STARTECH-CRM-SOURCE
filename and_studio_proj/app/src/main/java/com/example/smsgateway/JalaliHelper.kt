package com.example.smsgateway

import java.util.Calendar

object JalaliHelper {
    fun gregorianToJalali(year: Int, month: Int, day: Int): Triple<Int, Int, Int> {
        val gDM = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (month > 2) year + 1 else year
        var days = 355666 +
            (365 * year) +
            (gy2 + 3) / 4 -
            (gy2 + 99) / 100 +
            (gy2 + 399) / 400 +
            day +
            gDM[month - 1]

        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461

        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }

        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }

        return Triple(jy, jm, jd)
    }

    fun formatJalaliDate(year: Int, month: Int, day: Int): String {
        return String.format("%04d/%02d/%02d", year, month, day)
    }

    fun todayJalaliDate(): String {
        val calendar = Calendar.getInstance()
        val (jy, jm, jd) = gregorianToJalali(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        return formatJalaliDate(jy, jm, jd)
    }

    /** Persian (Jalali) date and time at registration, e.g. 1404/03/30 14:35:22 */
    fun nowJalaliDateTime(): String {
        val calendar = Calendar.getInstance()
        val (jy, jm, jd) = gregorianToJalali(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        val datePart = formatJalaliDate(jy, jm, jd)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        return String.format("%s %02d:%02d:%02d", datePart, hour, minute, second)
    }

    private val dateOnlyPattern = Regex("^\\d{4}/\\d{2}/\\d{2}$")

    /** Appends start-of-day time when only a Jalali date is provided. */
    fun normalizeFromDateParam(value: String): String {
        if (value.isEmpty()) return value
        return if (dateOnlyPattern.matches(value)) "$value 00:00:00" else value
    }

    /** Appends end-of-day time when only a Jalali date is provided. */
    fun normalizeToDateParam(value: String): String {
        if (value.isEmpty()) return value
        return if (dateOnlyPattern.matches(value)) "$value 23:59:59" else value
    }
}
