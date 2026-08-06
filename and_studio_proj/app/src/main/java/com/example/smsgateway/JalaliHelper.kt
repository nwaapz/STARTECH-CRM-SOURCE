package com.example.smsgateway

import java.util.Calendar
import java.util.Locale

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

    /**
     * Always Latin ASCII digits. The app locale is fa_IR; using the default locale in
     * String.format would store Persian/Arabic digits and break SQL date comparisons.
     */
    fun formatJalaliDate(year: Int, month: Int, day: Int): String {
        return String.format(Locale.US, "%04d/%02d/%02d", year, month, day)
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

    /** Jalali date for (today − months − days) using Gregorian calendar arithmetic. */
    fun jalaliDateOffsetMonthsDays(monthsAgo: Int, daysAgo: Int): String {
        val calendar = Calendar.getInstance()
        if (monthsAgo != 0) {
            calendar.add(Calendar.MONTH, -monthsAgo)
        }
        if (daysAgo != 0) {
            calendar.add(Calendar.DAY_OF_MONTH, -daysAgo)
        }
        val (jy, jm, jd) = gregorianToJalali(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        return formatJalaliDate(jy, jm, jd)
    }

    /** Persian (Jalali) date and time at registration, e.g. 1404/03/30 14:35:22 (Latin digits). */
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
        return String.format(Locale.US, "%s %02d:%02d:%02d", datePart, hour, minute, second)
    }

    private val dateOnlyPattern = Regex("^\\d{4}/\\d{2}/\\d{2}$")

    /** YYYY/MM/DD prefix with Latin digits, or null if missing/invalid. */
    fun datePrefixForCompare(storedDate: String?): String? {
        if (storedDate.isNullOrBlank()) return null
        val latin = PhoneUtils.normalizeToAsciiDigits(storedDate.trim())
        if (latin.length < 10) return null
        val prefix = latin.substring(0, 10)
        return prefix.takeIf { dateOnlyPattern.matches(it) }
    }

    /** True when [storedDate]'s day is on or before [onOrBeforeDate] (both Jalali YYYY/MM/DD). */
    fun isJalaliDateOnOrBefore(storedDate: String?, onOrBeforeDate: String): Boolean {
        val prefix = datePrefixForCompare(storedDate) ?: return false
        val cutoff = datePrefixForCompare(onOrBeforeDate) ?: onOrBeforeDate
        return prefix <= cutoff
    }

    /** True when [storedDate]'s calendar day equals [exactDate] (both Jalali YYYY/MM/DD). */
    fun isJalaliDateEqual(storedDate: String?, exactDate: String): Boolean {
        val prefix = datePrefixForCompare(storedDate) ?: return false
        val day = datePrefixForCompare(exactDate) ?: exactDate
        return prefix == day
    }

    /**
     * Normalizes registration timestamps to Latin digits (and date-only → start of day).
     * Safe to call on values that are already Latin.
     */
    fun normalizeStoredDateTime(value: String): String {
        val latin = PhoneUtils.normalizeToAsciiDigits(value.trim())
        if (latin.isEmpty()) return latin
        return if (isDateOnly(latin)) "$latin 00:00:00" else latin
    }

    /** Appends start-of-day time when only a Jalali date is provided. */
    fun normalizeFromDateParam(value: String): String {
        if (value.isEmpty()) return value
        val latin = PhoneUtils.normalizeToAsciiDigits(value.trim())
        return if (dateOnlyPattern.matches(latin)) "$latin 00:00:00" else latin
    }

    /** Appends end-of-day time when only a Jalali date is provided. */
    fun normalizeToDateParam(value: String): String {
        if (value.isEmpty()) return value
        val latin = PhoneUtils.normalizeToAsciiDigits(value.trim())
        return if (dateOnlyPattern.matches(latin)) "$latin 23:59:59" else latin
    }

    fun isDateOnly(value: String): Boolean =
        dateOnlyPattern.matches(PhoneUtils.normalizeToAsciiDigits(value.trim()))

    /**
     * Parses a CSV/DB registration timestamp into local stored form
     * (`YYYY/MM/DD HH:mm:ss` Jalali, Latin digits).
     *
     * Accepts Jalali (`1403/04/15…`) or Gregorian MySQL-style (`2025-12-21…`);
     * Gregorian is converted via [gregorianToJalali]. Returns null for empty / zero dates.
     */
    fun parseToStoredJalaliDateTime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val latin = PhoneUtils.normalizeToAsciiDigits(raw.trim())
        if (latin.isEmpty()) return null
        if (latin.startsWith("0000-00-00") || latin == "0") return null

        val match = DATE_TIME_PATTERN.matchEntire(latin) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null

        val hour = match.groupValues[4].toIntOrNull() ?: 0
        val minute = match.groupValues[5].toIntOrNull() ?: 0
        val second = match.groupValues[6].toIntOrNull() ?: 0

        val usesDash = latin.contains('-')
        val isGregorian = year >= 1700 || (usesDash && year !in 1200..1599)
        val (jy, jm, jd) = if (isGregorian) {
            gregorianToJalali(year, month, day)
        } else {
            Triple(year, month, day)
        }

        val datePart = formatJalaliDate(jy, jm, jd)
        return String.format(Locale.US, "%s %02d:%02d:%02d", datePart, hour, minute, second)
    }

    private val DATE_TIME_PATTERN = Regex(
        """^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?$""",
    )

    /** Days in Jalali month (month 1–12). Esfand leap approx via algorithm. */
    fun jalaliDaysInMonth(year: Int, month: Int): Int {
        return when {
            month in 1..6 -> 31
            month in 7..11 -> 30
            month == 12 -> if (isJalaliLeap(year)) 30 else 29
            else -> 30
        }
    }

    fun isJalaliLeap(year: Int): Boolean {
        val breaks = intArrayOf(
            -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
            1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
        )
        val jp = breaks
        var jump = 0
        var leapJ = -14
        var jpVal = jp[0]
        var jy = year
        if (jy < jpVal || jy >= jp[jp.size - 1]) {
            // Fallback: 33-year cycle approximation
            return ((year + 2346) % 33) in listOf(1, 5, 9, 13, 17, 22, 26, 30)
        }
        for (i in 1 until jp.size) {
            val jm = jp[i]
            jump = jm - jpVal
            if (jy < jm) break
            leapJ += jump / 33 * 8 + (jump % 33) / 4
            jpVal = jm
        }
        var n = jy - jpVal
        if (n < jump) {
            leapJ += n / 33 * 8 + (n % 33 + 3) / 4
            if ((jump % 33) == 4 && (jump - n) == 4) leapJ++
        }
        val leap = (((leapJ + 1) % 33) - 1) % 4
        return leap == -1 || leap == 0
    }

    /** First and last Jalali day of the current Persian month as YYYY/MM/DD. */
    fun currentJalaliMonthRange(): Pair<String, String> {
        val calendar = Calendar.getInstance()
        val (jy, jm, _) = gregorianToJalali(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        val last = jalaliDaysInMonth(jy, jm)
        return formatJalaliDate(jy, jm, 1) to formatJalaliDate(jy, jm, last)
    }
}
