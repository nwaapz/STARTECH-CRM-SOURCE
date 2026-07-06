package com.example.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return

        val settings = SettingsStore.getInstance(context)
        if (!settings.isAutoStart() || !settings.isServiceEnabled()) return
        if (!hasSmsPermission(context)) return

        GatewayForegroundService.start(context)
    }

    private fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
