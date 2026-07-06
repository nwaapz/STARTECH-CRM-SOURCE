package com.example.smsgateway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class IranProvince(
    val id: String,
    val name: String,
    val cities: List<String>,
)

object IranLocations {
    @Volatile
    private var cached: List<IranProvince>? = null

    fun load(context: Context): List<IranProvince> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: readFromAssets(context.applicationContext).also { cached = it }
        }
    }

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.trim()
            .replace('\u200c', ' ')
            .replace(Regex("\\s+"), " ")
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('ة', 'ه')
            .lowercase()
    }

    fun cityMatches(storedCity: String?, targetCity: String): Boolean {
        val normalizedStored = normalize(storedCity)
        val normalizedTarget = normalize(targetCity)
        if (normalizedStored.isEmpty() || normalizedTarget.isEmpty()) return false
        return normalizedStored == normalizedTarget ||
            normalizedStored.contains(normalizedTarget) ||
            normalizedTarget.contains(normalizedStored)
    }

    fun matchesLocation(
        storedCity: String?,
        provinceId: String?,
        cityName: String?,
        provinces: List<IranProvince>,
    ): Boolean {
        if (provinceId.isNullOrBlank()) return true
        val province = provinces.firstOrNull { it.id == provinceId } ?: return false
        if (cityName.isNullOrBlank()) {
            return province.cities.any { cityMatches(storedCity, it) }
        }
        return cityMatches(storedCity, cityName)
    }

    fun provinceName(provinceId: String?, provinces: List<IranProvince>): String? {
        if (provinceId.isNullOrBlank()) return null
        return provinces.firstOrNull { it.id == provinceId }?.name
    }

    private fun readFromAssets(context: Context): List<IranProvince> {
        val json = context.assets.open("iran-provinces.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(item.toProvince())
            }
        }
    }

    private fun JSONObject.toProvince(): IranProvince {
        val citiesArray = getJSONArray("cities")
        val cities = buildList {
            for (i in 0 until citiesArray.length()) {
                add(citiesArray.getString(i))
            }
        }
        return IranProvince(
            id = getString("id"),
            name = getString("name"),
            cities = cities,
        )
    }
}
