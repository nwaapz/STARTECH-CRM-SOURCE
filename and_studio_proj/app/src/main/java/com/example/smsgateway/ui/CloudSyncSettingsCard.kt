package com.example.smsgateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.cloud.CloudSyncProgress
import com.example.smsgateway.cloud.CloudSyncService
import com.example.smsgateway.cloud.SyncConflictChoice
import com.example.smsgateway.data.GatewayDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun CloudSyncSettingsCard(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
    openConflicts: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncService = remember { CloudSyncService.getInstance(context) }
    var url by remember { mutableStateOf(settingsStore.getCloudSyncUrl()) }
    var token by remember { mutableStateOf(settingsStore.getCloudSyncToken()) }
    var lastExchangeMs by remember { mutableStateOf(settingsStore.getCloudSyncLastPushMs()) }
    var pendingLocal by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var activeSyncJob by remember { mutableStateOf<Job?>(null) }
    var showConflictDialog by remember { mutableStateOf(false) }

    val progress by syncService.progressFlow.collectAsState()
    val conflictSession by syncService.conflictSessionFlow.collectAsState()
    val busy = progress.running

    val configured = url.isNotBlank() && token.isNotBlank()

    fun refreshMeta() {
        lastExchangeMs = maxOf(
            settingsStore.getCloudSyncLastPushMs(),
            settingsStore.getCloudSyncLastPullMs(),
        )
        scope.launch {
            pendingLocal = withContext(Dispatchers.IO) {
                GatewayDatabase.getInstance(context).syncOutboxDao().countPending()
            }
        }
    }

    fun formatMs(ms: Long): String {
        if (ms <= 0L) return "—"
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
    }

    fun phaseLabel(phase: String): String {
        return when (phase) {
            "fetch" -> context.getString(R.string.settings_cloud_sync_phase_fetch)
            "push" -> context.getString(R.string.settings_cloud_sync_phase_push)
            "apply" -> context.getString(R.string.settings_cloud_sync_phase_apply)
            "conflicts" -> context.getString(R.string.settings_cloud_sync_phase_conflicts)
            "done" -> context.getString(R.string.settings_cloud_sync_phase_done)
            "error" -> context.getString(R.string.settings_cloud_sync_phase_error)
            else -> context.getString(R.string.settings_cloud_sync_running)
        }
    }

    fun applyFinishedProgress(finalProgress: CloudSyncProgress) {
        activeSyncJob = null
        refreshMeta()
        statusOk = finalProgress.phase == "done"
        statusMessage = when (finalProgress.phase) {
            "done" -> context.getString(R.string.settings_cloud_sync_ok)
            "conflicts" -> finalProgress.detail
                ?: context.getString(R.string.settings_cloud_sync_phase_conflicts)
            "error" -> finalProgress.detail
                ?: context.getString(R.string.settings_cloud_sync_failed)
            else -> finalProgress.detail
        }
        if (finalProgress.phase == "conflicts" || syncService.getConflictSession() != null) {
            showConflictDialog = true
        }
    }

    LaunchedEffect(Unit) {
        refreshMeta()
        if (!syncService.isSyncRunning()) {
            if (syncService.getConflictSession() != null) showConflictDialog = true
            return@LaunchedEffect
        }
        syncService.progressFlow.collect { p ->
            if (!p.running) {
                applyFinishedProgress(p)
                return@collect
            }
        }
    }

    LaunchedEffect(openConflicts, conflictSession) {
        if (openConflicts || conflictSession != null) {
            showConflictDialog = conflictSession != null
        }
    }

    val session = conflictSession
    if (showConflictDialog && session != null) {
        SyncConflictDialog(
            session = session,
            onDismiss = { showConflictDialog = false },
            onConfirm = { resolutions: Map<String, SyncConflictChoice> ->
                showConflictDialog = false
                activeSyncJob = scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            syncService.pullWithResolutions(resolutions)
                        }
                        refreshMeta()
                        statusOk = result.ok && !result.needsConflictResolution
                        statusMessage = result.message
                        if (result.needsConflictResolution) {
                            showConflictDialog = true
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        statusOk = false
                        statusMessage = e.message
                            ?: context.getString(R.string.settings_cloud_sync_failed)
                    } finally {
                        activeSyncJob = null
                    }
                }
            },
        )
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_cloud_sync_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_cloud_sync_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (configured) {
                        R.string.settings_cloud_sync_status_ok
                    } else {
                        R.string.settings_cloud_sync_status_missing
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.settings_cloud_sync_pending,
                    progress.pendingLocal.takeIf { it > 0 } ?: pendingLocal,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                label = { Text(stringResource(R.string.settings_cloud_sync_url_label)) },
                placeholder = { Text(stringResource(R.string.settings_cloud_sync_url_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.settings_cloud_sync_token_label)) },
            )
            Text(
                text = stringResource(R.string.settings_cloud_sync_last, formatMs(lastExchangeMs)),
                style = MaterialTheme.typography.bodySmall,
            )

            val conflictCount = conflictSession?.conflicts?.size
                ?: progress.conflictCount
            if (conflictCount > 0) {
                Text(
                    text = stringResource(R.string.settings_cloud_sync_conflicts_badge, conflictCount),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { showConflictDialog = true },
                    enabled = conflictSession != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_cloud_sync_resolve))
                }
            }

            if (busy || progress.phase == "conflicts") {
                Text(phaseLabel(progress.phase), style = MaterialTheme.typography.bodySmall)
                if (busy) {
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                }
                progress.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    color = if (statusOk) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedButton(
                onClick = {
                    settingsStore.setCloudSyncUrl(url.trim())
                    settingsStore.setCloudSyncToken(token.trim())
                    statusOk = true
                    statusMessage = context.getString(R.string.settings_cloud_sync_saved)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_cloud_sync_save))
            }

            Text(
                text = stringResource(R.string.settings_cloud_sync_backfill_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        settingsStore.setCloudSyncUrl(url.trim())
                        settingsStore.setCloudSyncToken(token.trim())
                        activeSyncJob = scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    syncService.requestBackfillAndPull()
                                }
                                refreshMeta()
                                statusOk = result.ok && !result.needsConflictResolution
                                statusMessage = if (result.ok) {
                                    context.getString(R.string.settings_cloud_sync_get_ok)
                                } else if (result.needsConflictResolution) {
                                    result.message
                                } else {
                                    result.message
                                        ?: context.getString(R.string.settings_cloud_sync_get_failed)
                                }
                                if (result.needsConflictResolution) {
                                    showConflictDialog = true
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                statusOk = false
                                statusMessage = e.message
                                    ?: context.getString(R.string.settings_cloud_sync_get_failed)
                            } finally {
                                activeSyncJob = null
                            }
                        }
                    },
                    enabled = !busy && configured,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (busy) {
                            stringResource(R.string.settings_cloud_sync_get_running)
                        } else {
                            stringResource(R.string.settings_cloud_sync_get_button)
                        },
                    )
                }
                Button(
                    onClick = {
                        settingsStore.setCloudSyncUrl(url.trim())
                        settingsStore.setCloudSyncToken(token.trim())
                        activeSyncJob = scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    syncService.pushOnce()
                                }
                                refreshMeta()
                                statusOk = result.ok
                                statusMessage = if (result.ok) {
                                    result.message ?: context.getString(R.string.settings_cloud_sync_send_ok)
                                } else {
                                    result.message
                                        ?: context.getString(R.string.settings_cloud_sync_send_failed)
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                statusOk = false
                                statusMessage = e.message
                                    ?: context.getString(R.string.settings_cloud_sync_send_failed)
                            } finally {
                                activeSyncJob = null
                            }
                        }
                    },
                    enabled = !busy && configured,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (busy) {
                            stringResource(R.string.settings_cloud_sync_send_running)
                        } else {
                            stringResource(R.string.settings_cloud_sync_send_button)
                        },
                    )
                }
            }
        }
    }
}
