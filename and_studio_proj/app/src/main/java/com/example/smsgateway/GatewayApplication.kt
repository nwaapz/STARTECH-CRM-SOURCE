package com.example.smsgateway

import android.app.Application
import android.content.Context

class GatewayApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        RubikaChatStore.getInstance(this).syncPersistenceOnStartup()
        TelegramChatStore.getInstance(this).syncPersistenceOnStartup()
    }
}
