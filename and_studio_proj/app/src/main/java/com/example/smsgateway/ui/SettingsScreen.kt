package com.example.smsgateway.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smsgateway.GatewayForegroundService
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.R
import com.example.smsgateway.SerialGenerator
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.VerificationMode
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.license.LicenseState
import com.example.smsgateway.license.LicenseStore
import com.example.smsgateway.lan.LanHttpServer
import com.example.smsgateway.lan.LanNetworkUtils
import com.example.smsgateway.lan.LanQrCodeImage
import com.example.smsgateway.lan.LanStartupDiagnostics
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LAN_POLL_INTERVAL_MS = 2000L

/** The trace pane is a plain scrolling Column, so every line is measured even when off-screen. */
private const val MAX_VISIBLE_TRACE_LINES = 120

private data class LanPollSnapshot(
    val port: Int,
    val pin: String,
    val autoIp: String,
    val url: String,
    val usingCustomHost: Boolean,
    val serverRunning: Boolean,
    val traceLines: List<String>,
)

/** Blocking: enumerates network interfaces and formats trace entries. Call off the UI thread. */
private fun readLanSnapshot(context: Context, settingsStore: SettingsStore): LanPollSnapshot {
    val port = settingsStore.getLanPort()
    val autoIp = LanNetworkUtils.getLocalIpAddress(context)
    val override = settingsStore.getLanHostOverride()
    return LanPollSnapshot(
        port = port,
        pin = settingsStore.getLanPin(),
        autoIp = autoIp,
        url = "http://${override.ifBlank { autoIp }}:$port",
        usingCustomHost = override.isNotBlank(),
        serverRunning = LanHttpServer.getInstance(context).isRunning,
        traceLines = LanTraceLog.snapshot()
            .take(MAX_VISIBLE_TRACE_LINES)
            .map { it.formatted() },
    )
}

@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
    openSyncConflicts: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val generator = remember { SerialGenerator(GatewayDatabase.getInstance(context)) }

    val licenseStore = remember { LicenseStore.getInstance(context) }
    val licenseRevision by licenseStore.revision.collectAsState()
    val licenseSummary = remember(licenseRevision) { licenseStore.statusSummary() }

    var verificationMode by remember { mutableStateOf(settingsStore.getVerificationMode()) }
    var lanEnabled by remember { mutableStateOf(settingsStore.isLanServerEnabled()) }
    var lanUrl by remember { mutableStateOf("") }
    var lanAutoIp by remember { mutableStateOf("") }
    var lanHostDraft by remember { mutableStateOf(settingsStore.getLanHostOverride()) }
    var lanUsingCustomHost by remember { mutableStateOf(settingsStore.hasLanHostOverride()) }
    var lanPin by remember { mutableStateOf("") }
    var lanPort by remember { mutableIntStateOf(settingsStore.getLanPort()) }
    var dbTotal by remember { mutableIntStateOf(0) }
    var dbUnused by remember { mutableIntStateOf(0) }
    var lanTraceLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var lanServerRunning by remember { mutableStateOf(false) }
    val serviceRunning = GatewayForegroundService.isRunning(context)
    val appVersion = remember {
        getAppVersionName(context)
    }

    fun applyLanSnapshot(snapshot: LanPollSnapshot) {
        lanPort = snapshot.port
        lanPin = snapshot.pin
        lanAutoIp = snapshot.autoIp
        lanUrl = snapshot.url
        lanUsingCustomHost = snapshot.usingCustomHost
        lanServerRunning = snapshot.serverRunning
        lanTraceLines = snapshot.traceLines
    }

    fun refreshLanInfo() {
        scope.launch {
            applyLanSnapshot(withContext(Dispatchers.IO) { readLanSnapshot(context, settingsStore) })
        }
    }

    fun saveLanHostOverride() {
        val parsed = LanNetworkUtils.parseLanHostInput(lanHostDraft)
        if (parsed == null) {
            Toast.makeText(context, context.getString(R.string.settings_lan_host_override_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        settingsStore.setLanHostOverride(parsed.host)
        parsed.port?.let { settingsStore.setLanPort(it.coerceIn(1024, 65535)) }
        lanHostDraft = settingsStore.getLanHostOverride()
        lanUsingCustomHost = true
        refreshLanInfo()
        LanTraceLog.i("Settings", "User set custom CRM host=${parsed.host} port=${settingsStore.getLanPort()}")
        Toast.makeText(context, context.getString(R.string.settings_lan_host_override_saved), Toast.LENGTH_SHORT).show()
        GatewayForegroundService.refreshLanServer(context)
    }

    fun resetLanHostOverride() {
        settingsStore.clearLanHostOverride()
        lanHostDraft = ""
        lanUsingCustomHost = false
        refreshLanInfo()
        LanTraceLog.i("Settings", "User cleared custom CRM host — using auto-detect")
        GatewayForegroundService.refreshLanServer(context)
    }

    fun refreshStats() {
        scope.launch {
            val (total, unused) = generator.getStats()
            dbTotal = total
            dbUnused = unused
        }
    }

    LaunchedEffect(verificationMode, lanEnabled) {
        refreshStats()
        refreshLanInfo()
    }

    LaunchedEffect(lanEnabled) {
        if (!lanEnabled) return@LaunchedEffect
        while (true) {
            // Interface enumeration and trace formatting are far too slow to run on the
            // UI thread every tick; they were stalling the whole Settings tab.
            applyLanSnapshot(withContext(Dispatchers.IO) { readLanSnapshot(context, settingsStore) })
            delay(LAN_POLL_INTERVAL_MS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.license_section_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = licenseStateLabel(licenseSummary.state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = when (licenseSummary.state) {
                        com.example.smsgateway.license.LicenseState.ACTIVE ->
                            MaterialTheme.colorScheme.primary
                        com.example.smsgateway.license.LicenseState.GRACE,
                        com.example.smsgateway.license.LicenseState.NEVER_ACTIVATED,
                        -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                licenseSummary.licensedMsisdn?.let { msisdn ->
                    Text(
                        text = stringResource(
                            R.string.license_msisdn_label,
                            PhoneUtils.formatForDisplay(msisdn) ?: msisdn,
                        ),
                    )
                }
                if (licenseSummary.state == com.example.smsgateway.license.LicenseState.NEVER_ACTIVATED &&
                    licenseSummary.activationWindowRemainingMs > 0
                ) {
                    Text(
                        text = stringResource(
                            R.string.license_window_remaining,
                            formatDurationFa(licenseSummary.activationWindowRemainingMs),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (licenseSummary.state == com.example.smsgateway.license.LicenseState.GRACE &&
                    licenseSummary.graceRemainingMs > 0
                ) {
                    Text(
                        text = stringResource(
                            R.string.license_grace_remaining,
                            formatDurationFa(licenseSummary.graceRemainingMs),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = {
                        com.example.smsgateway.license.LicenseChecker.getInstance(context)
                            .requestCheckNow()
                        Toast.makeText(
                            context,
                            context.getString(R.string.license_check_sent),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    Text(stringResource(R.string.license_check_now))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_verification_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_verification_hint),
                    style = MaterialTheme.typography.bodySmall,
                )

                VerificationModeOption(
                    selected = verificationMode == VerificationMode.REMOTE,
                    title = stringResource(R.string.settings_mode_remote),
                    description = stringResource(R.string.settings_mode_remote_hint),
                    onClick = {
                        verificationMode = VerificationMode.REMOTE
                        settingsStore.setVerificationMode(VerificationMode.REMOTE)
                    },
                )
                VerificationModeOption(
                    selected = verificationMode == VerificationMode.INTERNAL,
                    title = stringResource(R.string.settings_mode_internal),
                    description = stringResource(R.string.settings_mode_internal_hint),
                    onClick = {
                        verificationMode = VerificationMode.INTERNAL
                        settingsStore.setVerificationMode(VerificationMode.INTERNAL)
                        refreshStats()
                    },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_lan_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_lan_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!serviceRunning) {
                    Text(
                        text = stringResource(R.string.settings_lan_service_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = stringResource(R.string.settings_lan_enable))
                    Switch(
                        checked = lanEnabled,
                        onCheckedChange = { enabled ->
                            lanEnabled = enabled
                            LanTraceLog.i("Settings", "User set LAN CRM enabled=$enabled")
                            settingsStore.setLanServerEnabled(enabled)
                            if (enabled) {
                                verificationMode = VerificationMode.INTERNAL
                            }
                            refreshLanInfo()
                            GatewayForegroundService.refreshLanServer(context)
                        },
                        enabled = serviceRunning,
                    )
                }
                if (lanEnabled) {
                    Text(
                        text = stringResource(R.string.settings_lan_internal_required),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.settings_lan_auto_ip, lanAutoIp))
                    if (lanUsingCustomHost) {
                        Text(
                            text = stringResource(R.string.settings_lan_host_override_active),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(stringResource(R.string.settings_lan_url, lanUrl))
                    OutlinedTextField(
                        value = lanHostDraft,
                        onValueChange = { lanHostDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_lan_host_override_label)) },
                        placeholder = { Text("28.144.123.105") },
                        singleLine = true,
                    )
                    Text(
                        text = stringResource(R.string.settings_lan_host_override_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { saveLanHostOverride() }) {
                            Text(stringResource(R.string.settings_lan_host_override_save))
                        }
                        if (lanUsingCustomHost || lanHostDraft.isNotBlank()) {
                            TextButton(onClick = { resetLanHostOverride() }) {
                                Text(stringResource(R.string.settings_lan_host_override_reset))
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (lanUrl.isNotBlank() && lanServerRunning) {
                            LanQrCodeImage(url = lanUrl)
                            Text(
                                text = stringResource(R.string.settings_lan_qr_hint),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.settings_lan_qr_waiting),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(stringResource(R.string.settings_lan_pin, lanPin))
                    Text(stringResource(R.string.settings_lan_port, lanPort))
                    TextButton(
                        onClick = {
                            copyToClipboard(context, lanUrl)
                            Toast.makeText(context, lanUrl, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("کپی آدرس CRM")
                    }
                    Text(
                        text = stringResource(
                            R.string.settings_lan_server_running,
                            if (lanServerRunning) {
                                stringResource(R.string.settings_lan_server_yes)
                            } else {
                                stringResource(R.string.settings_lan_server_no)
                            },
                        ),
                        color = if (lanServerRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = stringResource(R.string.settings_lan_test_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_lan_hotspot_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = {
                            if (!openHotspotSettings(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_lan_open_hotspot_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_lan_open_hotspot))
                    }
                }
            }
        }

        if (lanEnabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_lan_trace_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_lan_trace_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            lanTraceLines = LanTraceLog.snapshot()
                                .take(MAX_VISIBLE_TRACE_LINES)
                                .map { it.formatted() }
                        }) {
                            Text(stringResource(R.string.settings_lan_trace_refresh))
                        }
                        TextButton(onClick = {
                            copyToClipboard(context, LanTraceLog.snapshotText())
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Text(stringResource(R.string.settings_lan_trace_copy))
                        }
                        TextButton(onClick = {
                            LanTraceLog.clear()
                            lanTraceLines = LanTraceLog.snapshot()
                                .take(MAX_VISIBLE_TRACE_LINES)
                                .map { it.formatted() }
                        }) {
                            Text(stringResource(R.string.settings_lan_trace_clear))
                        }
                        TextButton(onClick = {
                            LanTraceLog.i("Settings", "Manual diagnostics requested")
                            LanStartupDiagnostics.run(
                                context,
                                settingsStore,
                                settingsStore.getLanPort(),
                            )
                            if (!LanHttpServer.getInstance(context).isRunning) {
                                LanTraceLog.w("Settings", "Server not running — attempting start")
                                GatewayForegroundService.refreshLanServer(context)
                            }
                        }) {
                            Text("اجرای تشخیص")
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (lanTraceLines.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_lan_trace_empty),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        } else {
                            lanTraceLines.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        SmsEngineSettingsCard(settingsStore = settingsStore)

        CloudSyncSettingsCard(
            settingsStore = settingsStore,
            openConflicts = openSyncConflicts,
        )

        BackupSettingsCard(settingsStore = settingsStore)

        SettingsProfileCard(settingsStore = settingsStore)

        if (verificationMode == VerificationMode.INTERNAL) {
            SmsProcessingSettingsCard(settingsStore = settingsStore)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.serial_db_stats),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.serial_db_total, dbTotal))
                    Text(stringResource(R.string.serial_db_unused, dbUnused))
                    Text(
                        text = stringResource(R.string.settings_internal_date_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.settings_app_version, appVersion),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun getAppVersionName(context: Context): String {
    return try {
        val packageManager = context.packageManager
        val packageName = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }
    } catch (_: Exception) {
        null
    } ?: "0.4"
}

@Composable
private fun licenseStateLabel(state: LicenseState): String {
    return stringResource(
        when (state) {
            LicenseState.NEVER_ACTIVATED -> R.string.license_state_never
            LicenseState.ACTIVE -> R.string.license_state_active
            LicenseState.GRACE -> R.string.license_state_grace
            LicenseState.DENIED -> R.string.license_state_denied
            LicenseState.LOCKED -> R.string.license_state_locked
        },
    )
}

private fun formatDurationFa(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0)
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes % (60 * 24)) / 60
    return when {
        days > 0 -> "$days روز و $hours ساعت"
        hours > 0 -> "$hours ساعت"
        else -> "${totalMinutes.coerceAtLeast(1)} دقیقه"
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("crm_url", text))
}

/** Opens the system hotspot / tethering settings page (user must toggle hotspot). */
private fun openHotspotSettings(context: Context): Boolean {
    val candidates = listOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent("android.settings.WIFI_TETHER_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_WIFI_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        } catch (_: ActivityNotFoundException) {
            // try next
        } catch (_: SecurityException) {
            // try next
        }
    }
    return false
}

@Composable
private fun VerificationModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
