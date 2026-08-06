package com.example.smsgateway.ui

import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.smsgateway.GatewayForegroundService
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.backup.SettingsProfileManager
import kotlinx.coroutines.launch

@Composable
fun SettingsProfileCard(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    fun restartApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) {
            context.startActivity(launch)
        }
        Process.killProcess(Process.myPid())
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        statusMessage = null
        scope.launch {
            val result = SettingsProfileManager.exportToUri(context, uri)
            busy = false
            statusOk = result.ok
            statusMessage = if (result.ok) {
                context.getString(R.string.settings_profile_save_ok)
            } else {
                result.message ?: context.getString(R.string.settings_profile_save_failed)
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        pendingImportUri = uri
    }

    fun runImport(uri: Uri) {
        busy = true
        statusMessage = null
        scope.launch {
            val result = SettingsProfileManager.importFromUri(context, uri)
            if (result.ok) {
                statusOk = true
                statusMessage = context.getString(R.string.settings_profile_load_restarting)
                GatewayForegroundService.stop(context)
                restartApp()
            } else {
                busy = false
                statusOk = false
                statusMessage = result.message ?: context.getString(R.string.settings_profile_load_failed)
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_profile_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_profile_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        createDocumentLauncher.launch(SettingsProfileManager.defaultFileName(context))
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_profile_save))
                }
                OutlinedButton(
                    onClick = {
                        openDocumentLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_profile_load))
                }
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusOk) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingImportUri = null },
            title = { Text(stringResource(R.string.settings_profile_load_title)) },
            text = { Text(stringResource(R.string.settings_profile_load_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = uri
                        pendingImportUri = null
                        runImport(picked)
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_profile_load))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingImportUri = null },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_backup_cancel))
                }
            },
        )
    }
}
