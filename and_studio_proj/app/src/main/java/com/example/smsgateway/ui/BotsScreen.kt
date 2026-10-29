package com.example.smsgateway.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore

/** Container tab hosting all bot screens (Rubika / Telegram / Bale) as sub-tabs. */
@Composable
fun BotsScreen(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    var selectedBot by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(
        stringResource(R.string.tab_rubika),
        stringResource(R.string.tab_telegram),
        stringResource(R.string.tab_bale),
    )

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedBot, modifier = Modifier.fillMaxWidth()) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedBot == index,
                    onClick = { selectedBot = index },
                    text = { Text(title) },
                )
            }
        }
        when (selectedBot) {
            0 -> RubikaScreen(
                settingsStore = settingsStore,
                modifier = Modifier.fillMaxSize(),
            )
            1 -> TelegramScreen(
                settingsStore = settingsStore,
                modifier = Modifier.fillMaxSize(),
            )
            else -> BaleScreen(
                settingsStore = settingsStore,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
