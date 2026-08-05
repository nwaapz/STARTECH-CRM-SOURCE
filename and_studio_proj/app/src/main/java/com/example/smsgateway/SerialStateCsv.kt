package com.example.smsgateway

import java.io.BufferedReader
import java.io.Reader

/**
 * Named-header CSV import for serial inventory **with** registration state.
 *
 * Required column: `serial`.
 * Optional: `phone`, `date` / `date_jalali`, `time` (Gregorian fallback), `city`, `km`.
 *
 * Used / unused is derived from phone (blank or `"0"` = unused), matching Room / CRM.
 * Dates: prefer Jalali columns; if missing/`NULL`, use `time`; Gregorian values are converted.
 */
object SerialStateCsv {
    const val MAX_ROWS = 750_000

    data class Row(
        val serial: String,
        val phone: String?,
        val date: String?,
        val city: String?,
        val km: Int?,
    )

    data class ParseStats(
        val emptyRows: Int,
        val invalidSamples: List<String>,
        val invalidCount: Int,
        val skippedInFileDuplicates: Int,
    )

    data class ParseResult(
        /** First occurrence wins for duplicate serials in the file. */
        val rowsBySerial: LinkedHashMap<String, Row>,
        val stats: ParseStats,
    )

    class CsvFormatException(message: String) : Exception(message)

    /**
     * Streams [reader] and builds a deduped row map.
     * @throws CsvFormatException when the header is missing `serial` or row count exceeds [MAX_ROWS]
     */
    fun parse(reader: Reader): ParseResult {
        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        val headerLine = buffered.readLine()
            ?: throw CsvFormatException("CSV file is empty")
        val headers = parseLine(headerLine).map { it.trim().lowercase() }
        if (headers.isEmpty() || headers.all { it.isEmpty() }) {
            throw CsvFormatException("CSV header row is empty")
        }

        val serialIdx = indexOfHeader(headers, SERIAL_ALIASES)
            ?: throw CsvFormatException(
                "CSV must include a serial column. Expected header: serial (optional: phone, date, city, km)",
            )
        val phoneIdx = indexOfHeader(headers, PHONE_ALIASES)
        val dateIdx = indexOfHeader(headers, DATE_ALIASES)
        val timeIdx = indexOfHeader(headers, TIME_ALIASES)
        val cityIdx = indexOfHeader(headers, CITY_ALIASES)
        val kmIdx = indexOfHeader(headers, KM_ALIASES)

        val rowsBySerial = LinkedHashMap<String, Row>()
        var emptyRows = 0
        var invalidCount = 0
        val invalidSamples = mutableListOf<String>()
        var skippedInFileDuplicates = 0
        var dataRowsSeen = 0

        while (true) {
            val line = buffered.readLine() ?: break
            if (line.isBlank()) {
                emptyRows++
                continue
            }
            dataRowsSeen++
            if (dataRowsSeen > MAX_ROWS) {
                throw CsvFormatException("Import exceeds maximum of $MAX_ROWS serials.")
            }

            val cols = parseLine(line)
            val rawSerial = cols.getOrNull(serialIdx)?.trim().orEmpty()
            if (rawSerial.isEmpty()) {
                emptyRows++
                continue
            }

            val serial = SerialNormalizer.normalizeSerial(rawSerial)
            if (serial.isEmpty() ||
                serial.length > SerialGenerator.MAX_SERIAL_LENGTH ||
                !SERIAL_REGEX.matches(serial)
            ) {
                invalidCount++
                if (invalidSamples.size < 5) invalidSamples.add(rawSerial)
                continue
            }

            val phoneRaw = phoneIdx?.let { cols.getOrNull(it) }?.let { csvCellOrNull(it) }
            val phone = PhoneUtils.nullIfUnregistered(
                phoneRaw?.let { PhoneUtils.normalizeToAsciiDigits(it) },
            )
            val datePrimary = dateIdx?.let { cols.getOrNull(it) }?.let { csvCellOrNull(it) }
            val dateFallback = timeIdx?.let { cols.getOrNull(it) }?.let { csvCellOrNull(it) }
            val date = if (phone != null) {
                resolveRegistrationDate(datePrimary, dateFallback)
            } else {
                null
            }
            val city = cityIdx?.let { cols.getOrNull(it) }?.let { csvCellOrNull(it) }
            val km = kmIdx?.let { idx ->
                csvCellOrNull(cols.getOrNull(idx).orEmpty())?.toIntOrNull()
            }

            if (rowsBySerial.containsKey(serial)) {
                skippedInFileDuplicates++
            } else {
                rowsBySerial[serial] = Row(
                    serial = serial,
                    phone = phone,
                    date = date,
                    city = city,
                    km = km,
                )
            }
        }

        return ParseResult(
            rowsBySerial = rowsBySerial,
            stats = ParseStats(
                emptyRows = emptyRows,
                invalidSamples = invalidSamples,
                invalidCount = invalidCount,
                skippedInFileDuplicates = skippedInFileDuplicates,
            ),
        )
    }

    /** Prefer Jalali date column; fall back to Gregorian `time` and convert as needed. */
    fun resolveRegistrationDate(primary: String?, fallback: String?): String? {
        JalaliHelper.parseToStoredJalaliDateTime(primary)?.let { return it }
        return JalaliHelper.parseToStoredJalaliDateTime(fallback)
    }

    /** Splits one CSV line; supports quoted fields with doubled quotes. */
    fun parseLine(line: String): List<String> {
        val result = ArrayList<String>(8)
        val sb = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> when (c) {
                    '"' -> {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            sb.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> sb.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    private fun indexOfHeader(headers: List<String>, aliases: Set<String>): Int? {
        headers.forEachIndexed { index, h ->
            if (h in aliases) return index
        }
        return null
    }

    /** Empty / SQL-null export tokens → null (phpMyAdmin often writes literal NULL). */
    fun csvCellOrNull(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (PhoneUtils.isSqlNullToken(trimmed)) return null
        return trimmed
    }

    private val SERIAL_ALIASES = setOf("serial", "serial_number", "serialno", "code")
    private val PHONE_ALIASES = setOf("phone", "mobile", "tel")
    private val DATE_ALIASES = setOf("date", "date_jalali", "datejalali", "jalali_date")
    private val TIME_ALIASES = setOf("time", "datetime", "created_at", "registered_at")
    private val CITY_ALIASES = setOf("city")
    private val KM_ALIASES = setOf("km", "mileage", "odometer")
    private val SERIAL_REGEX = Regex("^[A-Z0-9]+$")
}
