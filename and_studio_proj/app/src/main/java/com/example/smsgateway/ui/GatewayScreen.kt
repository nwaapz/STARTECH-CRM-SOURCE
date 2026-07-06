package com.example.smsgateway.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smsgateway.CommunicationLog
import com.example.smsgateway.GatewayForegroundService
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.SimCardHelper
import com.example.smsgateway.SimInfo
import com.example.smsgateway.data.CommunicationMessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GatewayScreen(
    settingsStore: SettingsStore,
    communicationLog: CommunicationLog,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val availableSims = remember { mutableStateListOf<SimInfo>() }
    var enabledSimIds by remember { mutableStateOf(settingsStore.getEnabledSubscriptionIds()) }

    LaunchedEffect(Unit) {
        settingsStore.ensureDefaultSimSelection()
        availableSims.clear()
        availableSims.addAll(SimCardHelper.getAvailableSims(context))
        settingsStore.reconcileEnabledSubscriptionIds()
        enabledSimIds = settingsStore.getEnabledSubscriptionIds()
    }

    var serviceRunning by remember { mutableStateOf(GatewayForegroundService.isRunning(context)) }
    var logEntries by remember { mutableStateOf(communicationLog.getLatest()) }
    var smsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
        )
    }
    var contactsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val refreshState = {
        serviceRunning = GatewayForegroundService.isRunning(context)
        logEntries = communicationLog.getLatest()
        smsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        contactsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_CONTACTS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshState() }

    DisposableEffect(communicationLog) {
        val listener = { logEntries = communicationLog.getLatest() }
        communicationLog.addListener(listener)
        onDispose { communicationLog.removeListener(listener) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StatusCard(running = serviceRunning)
        }
        if (availableSims.isNotEmpty()) {
            item {
                SimSelectionCard(
                    sims = availableSims,
                    enabledIds = enabledSimIds,
                    onToggle = { sim, checked ->
                        val updated = enabledSimIds.toMutableSet()
                        if (checked) {
                            updated.add(sim.subscriptionId)
                        } else if (updated.size > 1) {
                            updated.remove(sim.subscriptionId)
                        }
                        if (updated.isNotEmpty()) {
                            enabledSimIds = updated
                            settingsStore.setEnabledSubscriptionIds(updated)
                        }
                    },
                )
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.permissions),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PermissionChip(
                            label = stringResource(R.string.sms_permission),
                            granted = smsGranted,
                        )
                        PermissionChip(
                            label = stringResource(R.string.notification_permission),
                            granted = notificationGranted,
                        )
                        PermissionChip(
                            label = stringResource(R.string.contacts_permission),
                            granted = contactsGranted,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val permissions = buildList {
                                add(Manifest.permission.RECEIVE_SMS)
                                add(Manifest.permission.SEND_SMS)
                                add(Manifest.permission.READ_SMS)
                                add(Manifest.permission.READ_PHONE_STATE)
                                add(Manifest.permission.READ_CONTACTS)
                                add(Manifest.permission.WRITE_CONTACTS)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            permissionLauncher.launch(permissions.toTypedArray())
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.request_permissions))
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (serviceRunning) {
                            GatewayForegroundService.stop(context)
                        } else if (smsGranted) {
                            GatewayForegroundService.start(context)
                        } else {
                            val permissions = arrayOf(
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.READ_SMS,
                            )
                            permissionLauncher.launch(permissions)
                        }
                        refreshState()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (serviceRunning) {
                            stringResource(R.string.stop_gateway)
                        } else {
                            stringResource(R.string.start_gateway)
                        },
                    )
                }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.battery_settings))
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.activity_log),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = { communicationLog.clear() }) {
                    Text(stringResource(R.string.clear_log))
                }
            }
        }
        if (logEntries.isEmpty()) {
            item {
                Text(stringResource(R.string.no_messages_yet))
            }
        } else {
            items(logEntries) { entry ->
                CommunicationMessageCard(entry)
            }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (running) {
                    stringResource(R.string.gateway_running)
                } else {
                    stringResource(R.string.gateway_stopped)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (running) {
                    stringResource(R.string.gateway_running_hint)
                } else {
                    stringResource(R.string.gateway_stopped_hint)
                },
            )
        }
    }
}

@Composable
private fun SimSelectionCard(
    sims: List<SimInfo>,
    enabledIds: Set<Int>,
    onToggle: (SimInfo, Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sim_selection_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.sim_selection_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            sims.forEach { sim ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = sim.subscriptionId in enabledIds,
                        onCheckedChange = { checked -> onToggle(sim, checked) },
                    )
                    Text(sim.displayName)
                }
            }
        }
    }
}

@Composable
private fun PermissionChip(label: String, granted: Boolean) {
    SuggestionChip(
        onClick = {},
        label = {
            Text(
                "$label: ${if (granted) {
                    stringResource(R.string.permission_granted)
                } else {
                    stringResource(R.string.permission_denied)
                }}",
            )
        },
    )
}

@Composable
private fun statusLabel(status: String): String {
    return when (status) {
        "sent" -> stringResource(R.string.status_sent)
        "gateway_error" -> stringResource(R.string.status_gateway_error)
        "failed" -> stringResource(R.string.status_failed)
        else -> status
    }
}

@Composable
private fun CommunicationMessageCard(message: CommunicationMessageEntity) {
    val time = remember(message.timestamp) {
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale("fa", "IR")).format(Date(message.timestamp))
    }
    val isIncoming = message.direction == CommunicationLog.DIRECTION_INCOMING
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "${if (isIncoming) "↓" else "↑"} ${message.phone}" +
                    (message.status?.let { " • ${statusLabel(it)}" } ?: ""),
            )
            Text(time, style = MaterialTheme.typography.bodySmall)
            Text(
                if (isIncoming) {
                    "${stringResource(R.string.log_inbound)} ${message.body}"
                } else {
                    "${stringResource(R.string.log_outbound)} ${message.body}"
                },
            )
            message.simLabel?.let {
                Text("${stringResource(R.string.log_sim)} $it")
            }
        }
    }
}
