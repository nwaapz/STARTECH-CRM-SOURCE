package com.example.smsgateway

object SmsPatternParser {
    data class ParsedFields(
        val serial: String,
        val city: String?,
        val km: Int?,
    )

    enum class CombinedParseError {
        EMPTY,
        MISSING_SERIAL,
        MISSING_CITY,
        MISSING_KM,
        INVALID_KM,
        SERIAL_NOT_FOUND,
    }

    data class CombinedParseResult(
        val fields: ParsedFields? = null,
        val error: CombinedParseError? = null,
    )

    enum class CityKmAutoParseError {
        EMPTY,
        MISSING_KM,
        INVALID_KM,
        MISSING_CITY,
    }

    data class CityKmAutoParseResult(
        val fields: ParsedFields? = null,
        val error: CityKmAutoParseError? = null,
    )

    suspend fun parseCombinedFlexible(
        message: String,
        separator: Char,
        requiredFields: List<SmsField>,
        isValidSerial: suspend (String) -> Boolean,
    ): CombinedParseResult {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return CombinedParseResult(error = CombinedParseError.EMPTY)
        }

        val tokenSources = listOf(
            tokenizeBySeparator(trimmed, separator),
            tokenizeByWhitespace(trimmed),
            tokenizeSmart(trimmed),
        )

        for (tokens in tokenSources) {
            if (tokens.size != requiredFields.size) continue
            val parsed = tryPermutations(tokens, requiredFields, isValidSerial)
            if (parsed != null) {
                return CombinedParseResult(fields = parsed)
            }
        }

        return parseAutoCombined(trimmed, separator, requiredFields, isValidSerial)
    }

    fun parseSingleField(message: String, field: SmsField): ParsedFields? {
        val value = message.trim()
        if (value.isEmpty()) return null
        return when (field) {
            SmsField.SERIAL -> ParsedFields(serial = SerialNormalizer.normalizeSerial(value), city = null, km = null)
            SmsField.CITY -> ParsedFields(serial = "", city = value, km = null)
            SmsField.KM -> {
                val km = normalizeDigits(value).toIntOrNull() ?: return null
                ParsedFields(serial = "", city = null, km = km)
            }
        }
    }

    fun parseCityKmAuto(message: String): CityKmAutoParseResult {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return CityKmAutoParseResult(error = CityKmAutoParseError.EMPTY)
        }

        val normalized = normalizeDigits(trimmed)
        val digitMatches = DIGIT_REGEX.findAll(normalized).map { it.value }.toList()
        if (digitMatches.isEmpty()) {
            return CityKmAutoParseResult(error = CityKmAutoParseError.MISSING_KM)
        }

        val km = digitMatches.joinToString("").toIntOrNull()
            ?: return CityKmAutoParseResult(error = CityKmAutoParseError.INVALID_KM)

        val city = normalized.replace(DIGIT_REGEX, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (!isValidCityName(city)) {
            return CityKmAutoParseResult(error = CityKmAutoParseError.MISSING_CITY)
        }

        return CityKmAutoParseResult(
            fields = ParsedFields(serial = "", city = city, km = km),
        )
    }

    data class RemainingFields(
        val city: String?,
        val km: Int?,
    )

    /**
     * Best-effort extraction of whichever of city/km are still missing from a follow-up
     * SMS (typically the serial has already been stripped out by the caller). Tries
     * [parseCityKmAuto] first when both are missing (handles "Tehran 12000" in one go),
     * then falls back to single-field parsing per remaining field — this covers a
     * customer answering city and km in two separate messages.
     */
    fun extractRemainingFields(text: String, needsCity: Boolean, needsKm: Boolean): RemainingFields {
        if (!needsCity && !needsKm) return RemainingFields(city = null, km = null)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RemainingFields(city = null, km = null)

        if (needsCity && needsKm) {
            parseCityKmAuto(trimmed).fields?.let {
                return RemainingFields(city = it.city, km = it.km)
            }
        }

        // parseSingleField(CITY) does not itself validate the text looks like a city name
        // (it just returns the trimmed input) — guard here so a pure-digit follow-up meant
        // as km is never misread as the city too.
        val city = if (needsCity) {
            parseSingleField(trimmed, SmsField.CITY)?.city?.takeIf { isValidCityName(it) }
        } else {
            null
        }
        val km = if (needsKm) parseSingleField(trimmed, SmsField.KM)?.km else null
        return RemainingFields(city = city, km = km)
    }

    private suspend fun parseAutoCombined(
        message: String,
        separator: Char,
        requiredFields: List<SmsField>,
        isValidSerial: suspend (String) -> Boolean,
    ): CombinedParseResult {
        val needsKm = requiredFields.contains(SmsField.KM)
        val needsCity = requiredFields.contains(SmsField.CITY)
        val needsSerial = requiredFields.contains(SmsField.SERIAL)

        val serialCandidates = expandSerialCandidates(
            tokenizeBySeparator(message, separator) +
                tokenizeByWhitespace(message) +
                tokenizeSmart(message) +
                listOf(message.trim()),
        )

        for (serialCandidate in serialCandidates) {
            val serial = serialCandidate.trim()
            if (needsSerial && !isValidSerial(serial)) continue

            var km: Int? = null
            var city: String? = null

            if (needsKm || needsCity) {
                val remainder = removeToken(message, serial)
                val otherTokens = (
                    tokenizeBySeparator(remainder, separator) +
                        tokenizeByWhitespace(remainder) +
                        tokenizeSmart(remainder)
                    ).filter { it.isNotBlank() && !it.equals(serial, ignoreCase = true) }

                if (needsKm) {
                    km = otherTokens
                        .map { normalizeDigits(it.trim()) }
                        .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
                        ?.toIntOrNull()
                        ?: extractKmFromText(remainder)
                    if (km == null) {
                        return CombinedParseResult(error = CombinedParseError.INVALID_KM)
                    }
                }

                if (needsCity) {
                    city = extractCityFromTokens(otherTokens, remainder, separator)
                }
            }

            return CombinedParseResult(
                fields = ParsedFields(
                    serial = SerialNormalizer.normalizeSerial(serial),
                    city = city,
                    km = km,
                ),
            )
        }

        return CombinedParseResult(
            error = if (needsSerial) CombinedParseError.SERIAL_NOT_FOUND else CombinedParseError.MISSING_SERIAL,
        )
    }

    private suspend fun tryPermutations(
        tokens: List<String>,
        requiredFields: List<SmsField>,
        isValidSerial: suspend (String) -> Boolean,
    ): ParsedFields? {
        if (tokens.size != requiredFields.size) return null
        for (perm in permutations(tokens.indices.toList())) {
            val assignment = requiredFields.mapIndexed { index, field ->
                field to tokens[perm[index]].trim()
            }
            assignmentToFields(assignment, isValidSerial)?.let { return it }
        }
        return null
    }

    private suspend fun assignmentToFields(
        assignment: List<Pair<SmsField, String>>,
        isValidSerial: suspend (String) -> Boolean,
    ): ParsedFields? {
        var serial: String? = null
        var city: String? = null
        var km: Int? = null

        for ((field, token) in assignment) {
            when (field) {
                SmsField.SERIAL -> {
                    if (token.isBlank() || !isValidSerial(token)) return null
                    serial = SerialNormalizer.normalizeSerial(token)
                }
                SmsField.CITY -> {
                    if (token.isBlank() || !isValidCityName(token)) return null
                    city = token.trim()
                }
                SmsField.KM -> {
                    km = normalizeDigits(token).toIntOrNull() ?: return null
                }
            }
        }

        if (serial.isNullOrBlank()) return null
        return ParsedFields(serial = serial, city = city, km = km)
    }

    private fun tokenizeBySeparator(message: String, separator: Char): List<String> {
        if (!message.contains(separator)) return emptyList()
        return message.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun tokenizeByWhitespace(message: String): List<String> {
        return message.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun tokenizeSmart(message: String): List<String> {
        return message.split(Regex("\\s+"))
            .flatMap { chunk -> splitLetterDigitBoundaries(chunk) }
            .filter { it.isNotBlank() }
    }

    private fun splitLetterDigitBoundaries(chunk: String): List<String> {
        if (chunk.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val buffer = StringBuilder()
        var lastType: Char? = null

        chunk.forEach { char ->
            val type = if (char.isDigit()) 'D' else 'L'
            if (lastType != null && lastType != type) {
                tokens.add(buffer.toString())
                buffer.clear()
            }
            buffer.append(char)
            lastType = type
        }
        if (buffer.isNotEmpty()) {
            tokens.add(buffer.toString())
        }
        return tokens
    }

    private fun extractKmFromText(text: String): Int? {
        val digits = DIGIT_REGEX.findAll(normalizeDigits(text)).map { it.value }.toList()
        if (digits.isEmpty()) return null
        return digits.joinToString("").toIntOrNull()
    }

    private fun extractCityFromTokens(
        tokens: List<String>,
        remainder: String,
        separator: Char,
    ): String? {
        val fromTokens = tokens
            .map { it.trim() }
            .filter { isValidCityName(it) }
            .distinct()
            .joinToString(" ")
            .trim()
        if (fromTokens.isNotEmpty()) {
            return fromTokens
        }
        return extractCityFromText(remainder, separator)
    }

    private fun extractCityFromText(text: String, separator: Char = '#'): String? {
        val withoutSeparators = text
            .replace(separator.toString(), " ")
            .replace(Regex("[#*\\-]+"), " ")
        val cleaned = normalizeDigits(withoutSeparators)
            .replace(DIGIT_REGEX, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return cleaned.takeIf { isValidCityName(it) }
    }

    private fun isValidCityName(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (normalizeDigits(trimmed).all(Char::isDigit)) return false
        return trimmed.any { it.isLetter() }
    }

    private fun expandSerialCandidates(tokens: List<String>): List<String> {
        val filtered = tokens.map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = mutableListOf<String>()
        candidates.addAll(filtered)
        for (index in 0 until filtered.size - 1) {
            val left = filtered[index]
            val right = filtered[index + 1]
            if (left.isNotEmpty() && left.none(Char::isDigit) && right.all(Char::isDigit)) {
                candidates.add(left + right)
            }
        }
        return candidates.distinct()
    }

    private fun removeToken(message: String, token: String): String {
        return message
            .replace(Regex(Regex.escape(token), RegexOption.IGNORE_CASE), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        val result = mutableListOf<List<T>>()
        for (index in items.indices) {
            val element = items[index]
            val rest = items.filterIndexed { itemIndex, _ -> itemIndex != index }
            for (perm in permutations(rest)) {
                result.add(listOf(element) + perm)
            }
        }
        return result
    }

    private val DIGIT_REGEX = Regex("\\d+")

    private fun normalizeDigits(value: String): String {
        return buildString(value.length) {
            value.forEach { char ->
                append(
                    when (char) {
                        in '۰'..'۹' -> '0' + (char - '۰')
                        in '٠'..'٩' -> '0' + (char - '٠')
                        else -> char
                    },
                )
            }
        }
    }
}
