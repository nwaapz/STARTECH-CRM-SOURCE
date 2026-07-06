package com.example.smsgateway



import android.content.Context

import com.example.smsgateway.data.GatewayDatabase

import com.example.smsgateway.data.MassSmsAudienceType



data class MassSmsFilterRequest(

    val audienceType: String,

    val provinceId: String? = null,

    val cityName: String? = null,

    val serialGroupIds: List<Long>? = null,

    val registrationFromDate: String? = null,

    val registrationToDate: String? = null,

)



class MassSmsRecipientResolver(

    private val database: GatewayDatabase,

    private val context: Context,

) {

    suspend fun resolvePhones(filter: MassSmsFilterRequest): List<String> {

        val provinces = IranLocations.load(context)

        val hasGroupFilter = !filter.serialGroupIds.isNullOrEmpty()

        val (fromDate, toDate) = resolveDateParams(filter)

        val hasDateFilter = fromDate.isNotEmpty() || toDate.isNotEmpty()

        val hasRegisteredNarrowing = hasGroupFilter || hasDateFilter



        if (hasRegisteredNarrowing && filter.audienceType == MassSmsAudienceType.ERROR) {

            return emptyList()

        }



        val registered = resolveRegisteredPhones(filter, provinces, fromDate, toDate)

        val error = if (hasRegisteredNarrowing) emptyList() else resolveErrorPhones()



        val phones = when (filter.audienceType) {

            MassSmsAudienceType.REGISTERED -> registered

            MassSmsAudienceType.ERROR -> error

            MassSmsAudienceType.ALL -> {

                if (hasRegisteredNarrowing) {

                    registered

                } else {

                    (registered + error).distinctBy { phoneKey(it) }

                }

            }

            else -> emptyList()

        }



        return phones

            .mapNotNull { phone -> PhoneUtils.formatForDisplay(phone) ?: phone.trim().takeIf { it.isNotEmpty() } }

            .distinctBy { phoneKey(it) }

            .sorted()

    }



    private suspend fun resolveRegisteredPhones(

        filter: MassSmsFilterRequest,

        provinces: List<IranProvince>,

        fromDate: String,

        toDate: String,

    ): List<String> {

        val rows = if (filter.serialGroupIds.isNullOrEmpty()) {

            database.serialDao().listDistinctRegisteredPhones(fromDate, toDate)

        } else {

            database.serialDao().listDistinctRegisteredPhonesByGroupIds(

                filter.serialGroupIds,

                fromDate,

                toDate,

            )

        }

        return rows

            .filter { row ->

                IranLocations.matchesLocation(

                    storedCity = row.city,

                    provinceId = filter.provinceId,

                    cityName = filter.cityName,

                    provinces = provinces,

                )

            }

            .mapNotNull { it.phone }

    }



    private suspend fun resolveErrorPhones(): List<String> {

        return database.serialRegistrationErrorDao().listDistinctErrorPhonesExcludingRegistered()

    }



    private fun resolveDateParams(filter: MassSmsFilterRequest): Pair<String, String> {

        val fromDate = filter.registrationFromDate?.trim()?.takeIf { it.isNotEmpty() }

            ?.let { JalaliHelper.normalizeFromDateParam(it) }

            ?: ""

        val toDate = filter.registrationToDate?.trim()?.takeIf { it.isNotEmpty() }

            ?.let { JalaliHelper.normalizeToDateParam(it) }

            ?: ""

        return fromDate to toDate

    }



    private fun phoneKey(phone: String): String {

        return PhoneUtils.normalizeForSmsSend(phone)

    }



    companion object {

        fun getInstance(context: Context): MassSmsRecipientResolver {

            return MassSmsRecipientResolver(

                database = GatewayDatabase.getInstance(context.applicationContext),

                context = context.applicationContext,

            )

        }

    }

}


