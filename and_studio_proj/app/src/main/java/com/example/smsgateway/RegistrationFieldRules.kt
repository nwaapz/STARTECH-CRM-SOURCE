package com.example.smsgateway

/**
 * Shared rules for which registration fields are still required.
 *
 * - Seller serials (`seller_to_end_user`) never require km.
 * - If the phone already has a known city on file, city is not required again.
 */
object RegistrationFieldRules {
    data class MissingFields(
        val city: Boolean,
        val km: Boolean,
    ) {
        fun isEmpty(): Boolean = !city && !km

        fun toSmsFields(): List<SmsField> = buildList {
            if (city) add(SmsField.CITY)
            if (km) add(SmsField.KM)
        }
    }

    fun isSeller(category: String?): Boolean {
        return SerialGroupCategory.normalizeOrDefault(category) ==
            SerialGroupCategory.SELLER_TO_END_USER
    }

    fun requiresKm(category: String?): Boolean = !isSeller(category)

    /** Prefer row city; otherwise a previously known city for this phone. */
    fun effectiveCity(city: String?, knownCity: String?): String? {
        return city?.takeIf { it.isNotBlank() } ?: knownCity?.takeIf { it.isNotBlank() }
    }

    /**
     * KM already stored on an incomplete row. `0` is treated as unset — unused inventory /
     * CSV imports often leave `km=0`, which must not skip the KM prompt or finish Mode 2/3
     * after city-only follow-up. A km freshly parsed from the customer's SMS should be passed
     * through without this filter (including an explicit `0`).
     */
    fun storedKm(km: Int?): Int? = km?.takeUnless { it == 0 }

    fun missingFields(
        city: String?,
        km: Int?,
        category: String?,
        knownCity: String? = null,
    ): MissingFields {
        val missingCity = effectiveCity(city, knownCity).isNullOrBlank()
        val missingKm = requiresKm(category) && km == null
        return MissingFields(city = missingCity, km = missingKm)
    }

    fun needsCompletion(
        phone: String?,
        city: String?,
        km: Int?,
        category: String?,
        knownCity: String? = null,
    ): Boolean {
        if (phone.isNullOrBlank()) return false
        return !missingFields(city, km, category, knownCity).isEmpty()
    }

    fun fieldsComplete(
        city: String?,
        km: Int?,
        category: String?,
        knownCity: String? = null,
    ): Boolean = missingFields(city, km, category, knownCity).isEmpty()
}
