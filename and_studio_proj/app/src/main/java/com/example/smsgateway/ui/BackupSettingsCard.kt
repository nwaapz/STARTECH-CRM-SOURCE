package com.example.smsgateway.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.backup.BackupFileInfo
import com.example.smsgateway.backup.BackupReason
import com.example.smsgateway.backup.BackupScheduler
import com.example.smsgateway.backup.BackupStorageTarget
import com.example.smsgateway.backup.GatewayBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupSettingsCard(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var targets by remember { mutableStateOf(emptyList<BackupStorageTarget>()) }
    var selectedId by remember { mutableStateOf(settingsStore.getBackupAutoTargetId()) }
    var autoEnabled by remember { mutableStateOf(settingsStore.isBackupAutoEnabled()) }
    var lastSuccessMs by remember { mutableStateOf(settingsStore.getBackupLastSuccessMs()) }
    var backups by remember { mutableStateOf(emptyList<BackupFileInfo>()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressDetail by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var pendingRestore by remember { mutableStateOf<BackupFileInfo?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDelete by remember { mutableStateOf<BackupFileInfo?>(null) }

    val selected = targets.firstOrNull { it.id == selectedId } ?: targets.firstOrNull()

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        statusMessage = null
        progress = 0.05f
        progressDetail = null
        scope.launch {
            val result = GatewayBackupManager.createBackupToUri(
                context = context,
                uri = uri,
                reason = BackupReason.MANUAL,
            ) { p ->
                mainHandler.post {
                    progress = p.percent / 100f
                    progressDetail = p.detail
                }
            }
            withContext(Dispatchers.Main) {
                busy = false
                statusOk = result.ok
                statusMessage = if (result.ok) {
                    settingsStore.setBackupLastSuccessMs(System.currentTimeMillis())
                    lastSuccessMs = settingsStore.getBackupLastSuccessMs()
                    context.getString(R.string.settings_backup_save_picker_ok)
                } else {
                    result.message ?: context.getString(R.string.settings_backup_create_failed)
                }
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        pendingRestoreUri = uri
    }

    fun labelFor(target: BackupStorageTarget): String {
        val resId = when (target.labelKey) {
            "settings_backup_storage_internal" -> R.string.settings_backup_storage_internal
            "settings_backup_storage_shared" -> R.string.settings_backup_storage_shared
            "settings_backup_storage_sd" -> R.string.settings_backup_storage_sd
            else -> R.string.settings_backup_storage_external
        }
        return context.getString(resId)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun formatMs(ms: Long): String {
        if (ms <= 0L) return "—"
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val listed = GatewayBackupManager.listStorageTargets(context)
            var nextSelected = settingsStore.getBackupAutoTargetId()
            if (nextSelected.isBlank() || listed.none { it.id == nextSelected }) {
                nextSelected = GatewayBackupManager.preferredAutoTarget(context, null)?.id.orEmpty()
                if (nextSelected.isNotBlank()) {
                    settingsStore.setBackupAutoTargetId(nextSelected)
                }
            }
            val target = listed.firstOrNull { it.id == nextSelected }
            val listedBackups = if (target != null) GatewayBackupManager.listBackups(target) else emptyList()
            val lastMs = settingsStore.getBackupLastSuccessMs()
            withContext(Dispatchers.Main) {
                targets = listed
                selectedId = nextSelected
                backups = listedBackups
                lastSuccessMs = lastMs
            }
        }
    }

    fun restartApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) {
            context.startActivity(launch)
        }
        Process.killProcess(Process.myPid())
    }

    fun runRestore(zipFile: java.io.File) {
        busy = true
        statusMessage = null
        progress = 0.05f
        scope.launch {
            val result = GatewayBackupManager.restoreBackup(
                context = context,
                zipFile = zipFile,
            ) { p ->
                mainHandler.post {
                    progress = p.percent / 100f
                    progressDetail = p.detail
                }
            }
            if (result.ok) {
                statusOk = true
                statusMessage = context.getString(R.string.settings_backup_restore_restarting)
                progress = 1f
                restartApp()
            } else {
                busy = false
                statusOk = false
                statusMessage = result.message
                    ?: context.getString(R.string.settings_backup_restore_failed)
            }
        }
    }

    fun runRestoreFromUri(uri: Uri) {
        busy = true
        statusMessage = null
        progress = 0.05f
        scope.launch {
            val result = GatewayBackupManager.restoreBackupFromUri(
                context = context,
                uri = uri,
            ) { p ->
                mainHandler.post {
                    progress = p.percent / 100f
                    progressDetail = p.detail
                }
            }
            if (result.ok) {
                statusOk = true
                statusMessage = context.getString(R.string.settings_backup_restore_restarting)
                progress = 1f
                restartApp()
            } else {
                busy = false
                statusOk = false
                statusMessage = result.message
                    ?: context.getString(R.string.settings_backup_restore_failed)
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_backup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_backup_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = stringResource(R.string.settings_backup_picker_section),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.settings_backup_picker_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        createDocumentLauncher.launch(
                            GatewayBackupManager.defaultBackupFileName(context, BackupReason.MANUAL),
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_backup_save_picker))
                }
                OutlinedButton(
                    onClick = {
                        openDocumentLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_backup_restore_picker))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_backup_auto_daily),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoEnabled,
                    onCheckedChange = {
                        autoEnabled = it
                        settingsStore.setBackupAutoEnabled(it)
                        if (it) BackupScheduler.getInstance(context).start()
                    },
                    enabled = !busy,
                )
            }

            Text(
                text = stringResource(
                    R.string.settings_backup_last_auto,
                    formatMs(lastSuccessMs),
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = stringResource(R.string.settings_backup_storage_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (targets.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_backup_no_storage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(targets, key = { it.id }) { target ->
                        FilterChip(
                            selected = target.id == selected?.id,
                            onClick = {
                                if (busy) return@FilterChip
                                selectedId = target.id
                                settingsStore.setBackupAutoTargetId(target.id)
                                backups = GatewayBackupManager.listBackups(target)
                            },
                            label = {
                                Text(
                                    "${labelFor(target)} (${formatBytes(target.freeBytes)} ${
                                        context.getString(R.string.settings_backup_free)
                                    })",
                                )
                            },
                            enabled = !busy,
                        )
                    }
                }
            }

            if (busy) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
                Text(
                    text = progressDetail
                        ?: stringResource(R.string.settings_backup_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            Button(
                onClick = {
                    val target = selected ?: return@Button
                    busy = true
                    statusMessage = null
                    progress = 0.05f
                    progressDetail = null
                    scope.launch {
                        val result = GatewayBackupManager.createBackup(
                            context = context,
                            target = target,
                            reason = BackupReason.MANUAL,
                        ) { p ->
                            mainHandler.post {
                                progress = p.percent / 100f
                                progressDetail = p.detail
                            }
                        }
                        withContext(Dispatchers.Main) {
                            busy = false
                            statusOk = result.ok
                            statusMessage = if (result.ok) {
                                settingsStore.setBackupLastSuccessMs(System.currentTimeMillis())
                                lastSuccessMs = settingsStore.getBackupLastSuccessMs()
                                context.getString(
                                    R.string.settings_backup_create_ok,
                                    result.file?.name ?: "",
                                )
                            } else {
                                result.message
                                    ?: context.getString(R.string.settings_backup_create_failed)
                            }
                        }
                        withContext(Dispatchers.IO) {
                            val listed = GatewayBackupManager.listBackups(target)
                            withContext(Dispatchers.Main) {
                                backups = listed
                                lastSuccessMs = settingsStore.getBackupLastSuccessMs()
                            }
                        }
                    }
                },
                enabled = !busy && selected != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_backup_create))
            }

            Text(
                text = stringResource(R.string.settings_backup_list_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (backups.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_backup_list_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                backups.take(20).forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = buildString {
                                append(formatBytes(item.sizeBytes))
                                append(" — ")
                                append(formatMs(item.modifiedMs))
                                if (item.isDaily) {
                                    append(" — ")
                                    append(context.getString(R.string.settings_backup_badge_daily))
                                } else {
                                    append(" — ")
                                    append(context.getString(R.string.settings_backup_badge_manual))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { pendingRestore = item },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.settings_backup_restore))
                            }
                            OutlinedButton(
                                onClick = { pendingDelete = item },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.settings_backup_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRestore?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRestore = null },
            title = { Text(stringResource(R.string.settings_backup_restore_title)) },
            text = {
                Text(stringResource(R.string.settings_backup_restore_confirm, item.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRestore = null
                        runRestore(item.file)
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_backup_restore))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRestore = null },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_backup_cancel))
                }
            },
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRestoreUri = null },
            title = { Text(stringResource(R.string.settings_backup_restore_title)) },
            text = {
                Text(stringResource(R.string.settings_backup_restore_picker_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = uri
                        pendingRestoreUri = null
                        runRestoreFromUri(picked)
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_backup_restore))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRestoreUri = null },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_backup_cancel))
                }
            },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.settings_backup_delete)) },
            text = {
                Text(stringResource(R.string.settings_backup_delete_confirm, item.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        GatewayBackupManager.deleteBackup(item.file)
                        pendingDelete = null
                        backups = selected?.let { GatewayBackupManager.listBackups(it) }.orEmpty()
                    },
                ) {
                    Text(stringResource(R.string.settings_backup_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.settings_backup_cancel))
                }
            },
        )
    }
}
