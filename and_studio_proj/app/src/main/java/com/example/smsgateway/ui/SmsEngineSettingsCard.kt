package com.example.smsgateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.SmsEngineMode

@Composable
fun SmsEngineSettingsCard(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    var engineMode by remember { mutableStateOf(settingsStore.getSmsEngineMode()) }
    var username by remember { mutableStateOf(settingsStore.getMelipayamakUsername()) }
    var password by remember { mutableStateOf(settingsStore.getMelipayamakPassword()) }
    var senderLine by remember { mutableStateOf(settingsStore.getMelipayamakSenderLine()) }
    var inboundShortCodes by remember {
        mutableStateOf(settingsStore.getMelipayamakInboundShortCodes().joinToString(","))
    }
    var testModeEnabled by remember { mutableStateOf(settingsStore.isMelipayamakTestModeEnabled()) }
    var testPhone by remember { mutableStateOf(settingsStore.getMelipayamakTestPhone()) }
    var respondViaDeviceSim by remember { mutableStateOf(settingsStore.isMelipayamakRespondViaDeviceSim()) }

    fun persistMode(mode: SmsEngineMode) {
        engineMode = mode
        settingsStore.setSmsEngineMode(mode)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_sms_engine_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_sms_engine_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            SmsEngineModeOption(
                selected = engineMode == SmsEngineMode.DEVICE_SIM,
                title = stringResource(R.string.settings_sms_engine_device),
                description = stringResource(R.string.settings_sms_engine_device_hint),
                onClick = { persistMode(SmsEngineMode.DEVICE_SIM) },
            )
            SmsEngineModeOption(
                selected = engineMode == SmsEngineMode.MELIPAYAMAK_PANEL,
                title = stringResource(R.string.settings_sms_engine_panel),
                description = stringResource(R.string.settings_sms_engine_panel_hint),
                onClick = { persistMode(SmsEngineMode.MELIPAYAMAK_PANEL) },
            )

            if (engineMode == SmsEngineMode.MELIPAYAMAK_PANEL) {
                Text(
                    text = stringResource(R.string.settings_sms_engine_panel_inbound_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_sms_engine_panel_respond_device),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_sms_engine_panel_respond_device_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = respondViaDeviceSim,
                        onCheckedChange = {
                            respondViaDeviceSim = it
                            settingsStore.setMelipayamakRespondViaDeviceSim(it)
                        },
                    )
                }
                if (!respondViaDeviceSim) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        settingsStore.setMelipayamakUsername(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_sms_engine_panel_username)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        settingsStore.setMelipayamakPassword(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_sms_engine_panel_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = senderLine,
                    onValueChange = {
                        senderLine = it
                        settingsStore.setMelipayamakSenderLine(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_sms_engine_panel_sender)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                }
                OutlinedTextField(
                    value = inboundShortCodes,
                    onValueChange = {
                        inboundShortCodes = it
                        settingsStore.setMelipayamakInboundShortCodes(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_sms_engine_panel_inbound_codes)) },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_sms_engine_panel_test_mode),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_sms_engine_panel_test_mode_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = testModeEnabled,
                        onCheckedChange = {
                            testModeEnabled = it
                            settingsStore.setMelipayamakTestModeEnabled(it)
                        },
                    )
                }
                if (testModeEnabled) {
                    OutlinedTextField(
                        value = testPhone,
                        onValueChange = {
                            testPhone = it
                            settingsStore.setMelipayamakTestPhone(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_sms_engine_panel_test_phone)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            }
        }
    }
}

@Composable
private fun SmsEngineModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
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
