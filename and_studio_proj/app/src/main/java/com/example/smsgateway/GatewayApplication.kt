package com.example.smsgateway

import android.app.Application
import android.content.Context
import kotlinx.coroutines.launch

class GatewayApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        RubikaChatStore.getInstance(this).syncPersistenceOnStartup()
        TelegramChatStore.getInstance(this).syncPersistenceOnStartup()
        BaleChatStore.getInstance(this).syncPersistenceOnStartup()
        // Ensure default CRM admin exists for LAN user/password login
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            CrmAuthService.getInstance(this@GatewayApplication).ensureBootstrapAdmin()
        }
    }
}
