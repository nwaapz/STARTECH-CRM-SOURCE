package com.example.smsgateway

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private val PERSIAN = Locale("fa", "IR")

    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(PERSIAN)
        return context.createConfigurationContext(config)
    }
}
