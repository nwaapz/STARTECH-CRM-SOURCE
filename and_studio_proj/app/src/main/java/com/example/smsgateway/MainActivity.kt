package com.example.smsgateway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import com.example.smsgateway.BalePoller
import com.example.smsgateway.RubikaPoller
import com.example.smsgateway.TelegramPoller
import com.example.smsgateway.cloud.CloudSyncService
import com.example.smsgateway.license.LicenseGate
import com.example.smsgateway.license.LicenseStore
import com.example.smsgateway.ui.BotsScreen
import com.example.smsgateway.ui.GatewayScreen
import com.example.smsgateway.ui.LicenseLockOverlay
import com.example.smsgateway.ui.ReportsScreen
import com.example.smsgateway.ui.SerialGenerationScreen
import com.example.smsgateway.ui.SettingsScreen
import com.example.smsgateway.ui.SmsMessagesScreen
import com.example.smsgateway.ui.theme.SmsGatewayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openConflicts = intent?.getBooleanExtra(
            CloudSyncService.EXTRA_OPEN_SYNC_CONFLICTS,
            false,
        ) == true
        setContent {
            SmsGatewayTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    GatewayApp(openSyncConflicts = openConflicts)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

private enum class AppTab(val titleRes: Int) {
    Gateway(R.string.tab_gateway),
    SerialGeneration(R.string.tab_serial_generation),
    Messages(R.string.tab_messages),
    Bots(R.string.tab_bots),
    Reports(R.string.tab_reports),
    Settings(R.string.tab_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayApp(openSyncConflicts: Boolean = false) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore.getInstance(context) }
    val communicationLog = remember { CommunicationLog.getInstance(context) }
    var selectedTab by remember {
        mutableIntStateOf(
            if (openSyncConflicts) AppTab.Settings.ordinal else AppTab.Gateway.ordinal,
        )
    }

    LaunchedEffect(communicationLog) {
        communicationLog.migrateFromActivityLogIfNeeded()
        communicationLog.backfillIfEmpty()
    }

    val licenseStore = remember { LicenseStore.getInstance(context) }
    val licenseRevision by licenseStore.revision.collectAsState()
    LaunchedEffect(licenseRevision) {
        val licenseOk = LicenseGate.allowsOperationalUse(context)
        if (licenseOk && settingsStore.isRubikaBotActive()) {
            RubikaPoller.getInstance(context).start()
        }
        if (licenseOk && settingsStore.isTelegramBotActive()) {
            TelegramPoller.getInstance(context).start()
        }
        if (licenseOk && settingsStore.isBaleBotActive()) {
            BalePoller.getInstance(context).start()
        }
    }

    val tabs = AppTab.entries
    val currentTab = tabs[selectedTab]

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(currentTab.titleRes)) })
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        AppTab.Gateway -> Icons.Filled.Message
                                        AppTab.SerialGeneration -> Icons.Filled.Build
                                        AppTab.Messages -> Icons.Filled.Chat
                                        AppTab.Bots -> Icons.Filled.SmartToy
                                        AppTab.Reports -> Icons.AutoMirrored.Filled.List
                                        AppTab.Settings -> Icons.Filled.Settings
                                    },
                                    contentDescription = stringResource(tab.titleRes),
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(tab.titleRes),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            },
        ) { innerPadding ->
            when (currentTab) {
                AppTab.Gateway -> GatewayScreen(
                    settingsStore = settingsStore,
                    communicationLog = communicationLog,
                    modifier = Modifier.padding(innerPadding),
                )
                AppTab.SerialGeneration -> SerialGenerationScreen(
                    modifier = Modifier.padding(innerPadding),
                )
                AppTab.Messages -> SmsMessagesScreen(
                    settingsStore = settingsStore,
                    modifier = Modifier.padding(innerPadding),
                )
                AppTab.Bots -> BotsScreen(
                    settingsStore = settingsStore,
                    modifier = Modifier.padding(innerPadding),
                )
                AppTab.Reports -> ReportsScreen(
                    modifier = Modifier.padding(innerPadding),
                )
                AppTab.Settings -> SettingsScreen(
                    settingsStore = settingsStore,
                    modifier = Modifier.padding(innerPadding),
                    openSyncConflicts = openSyncConflicts,
                )
            }
        }

        LicenseLockOverlay()
    }
}
