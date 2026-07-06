package com.example.smsgateway.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.SmsField
import com.example.smsgateway.SmsProcessingConfig
import com.example.smsgateway.SmsProcessingMode
import com.example.smsgateway.StepFlowType

@Composable
fun SmsProcessingSettingsCard(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    var config by remember { mutableStateOf(settingsStore.getSmsProcessingConfig()) }
    var sessionTimeoutText by remember {
        mutableStateOf(config.sessionTimeoutMinutes.toString())
    }

    fun persist(updated: SmsProcessingConfig) {
        val validated = updated.copy(
            sessionTimeoutMinutes = sessionTimeoutText.toIntOrNull()?.coerceIn(1, 1440)
                ?: updated.sessionTimeoutMinutes,
        ).normalized()
        val error = validated.validate()
        if (error != null) return
        settingsStore.setSmsProcessingConfig(validated)
        config = validated
        sessionTimeoutText = validated.sessionTimeoutMinutes.toString()
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_sms_processing_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_sms_processing_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            SmsModeOption(
                selected = config.mode == SmsProcessingMode.SERIAL_ONLY,
                title = stringResource(R.string.settings_sms_mode_serial_only),
                description = stringResource(R.string.settings_sms_mode_serial_only_hint),
                onClick = { persist(config.copy(mode = SmsProcessingMode.SERIAL_ONLY)) },
            )
            SmsModeOption(
                selected = config.mode == SmsProcessingMode.COMBINED_PATTERN,
                title = stringResource(R.string.settings_sms_mode_combined),
                description = stringResource(R.string.settings_sms_mode_combined_hint),
                onClick = { persist(config.copy(mode = SmsProcessingMode.COMBINED_PATTERN)) },
            )
            SmsModeOption(
                selected = config.mode == SmsProcessingMode.STEP_BY_STEP,
                title = stringResource(R.string.settings_sms_mode_step),
                description = stringResource(R.string.settings_sms_mode_step_hint),
                onClick = { persist(config.copy(mode = SmsProcessingMode.STEP_BY_STEP)) },
            )

            when (config.mode) {
                SmsProcessingMode.COMBINED_PATTERN -> {
                    Text(
                        text = stringResource(R.string.settings_sms_mode_combined_example),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SmsProcessingMode.STEP_BY_STEP -> {
                    StepFlowTypePicker(
                        selected = config.stepFlowType,
                        onSelected = { persist(config.copy(stepFlowType = it)) },
                    )
                    if (config.stepFlowType == StepFlowType.SEQUENTIAL) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.settings_sms_step_order_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.settings_sms_step_order_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            ReorderableFieldList(
                                fields = config.stepFieldOrder,
                                lockFirstSerial = true,
                                onReorder = { updated ->
                                    config = config.copy(stepFieldOrder = updated)
                                },
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_sms_step_combined_example),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedTextField(
                        value = sessionTimeoutText,
                        onValueChange = { sessionTimeoutText = it.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.settings_sms_session_timeout)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                SmsProcessingMode.SERIAL_ONLY -> Unit
            }

            androidx.compose.material3.TextButton(
                onClick = { persist(config) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.settings_sms_save))
            }
        }
    }
}

@Composable
private fun SmsModeOption(
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

@Composable
private fun StepFlowTypePicker(
    selected: StepFlowType,
    onSelected: (StepFlowType) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_sms_step_flow_title),
            style = MaterialTheme.typography.titleSmall,
        )
        SmsModeOption(
            selected = selected == StepFlowType.SEQUENTIAL,
            title = stringResource(R.string.settings_sms_step_flow_sequential),
            description = stringResource(R.string.settings_sms_step_flow_sequential_hint),
            onClick = { onSelected(StepFlowType.SEQUENTIAL) },
        )
        SmsModeOption(
            selected = selected == StepFlowType.COMBINED_SECOND,
            title = stringResource(R.string.settings_sms_step_flow_combined),
            description = stringResource(R.string.settings_sms_step_flow_combined_hint),
            onClick = { onSelected(StepFlowType.COMBINED_SECOND) },
        )
    }
}

@Composable
private fun ReorderableFieldList(
    fields: List<SmsField>,
    lockFirstSerial: Boolean,
    onReorder: (List<SmsField>) -> Unit,
) {
    var items by remember { mutableStateOf(fields) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(fields) {
        if (draggedIndex == -1) {
            items = fields
        }
    }

    fun moveItem(fromIndex: Int, toIndex: Int, notifyParent: Boolean = true) {
        if (fromIndex == toIndex) return
        if (lockFirstSerial && (fromIndex == 0 || toIndex == 0)) return
        val mutable = items.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        items = mutable
        if (notifyParent) {
            onReorder(mutable)
        }
    }

    fun minIndex(): Int = if (lockFirstSerial) 1 else 0

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, field ->
            val locked = lockFirstSerial && index == 0 && field == SmsField.SERIAL
            Surface(
                tonalElevation = if (index == draggedIndex) 4.dp else 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!locked) {
                            Modifier.pointerInput(index, items.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedIndex = index
                                        accumulatedDragY = 0f
                                    },
                                    onDragEnd = {
                                        if (draggedIndex >= 0) {
                                            onReorder(items)
                                        }
                                        draggedIndex = -1
                                        accumulatedDragY = 0f
                                    },
                                    onDragCancel = {
                                        draggedIndex = -1
                                        accumulatedDragY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val currentIndex = draggedIndex
                                        if (currentIndex < 0) return@detectDragGesturesAfterLongPress
                                        accumulatedDragY += dragAmount.y
                                        when {
                                            accumulatedDragY > DRAG_SWAP_THRESHOLD_PX &&
                                                currentIndex < items.lastIndex -> {
                                                moveItem(currentIndex, currentIndex + 1, notifyParent = false)
                                                draggedIndex = currentIndex + 1
                                                accumulatedDragY = 0f
                                            }
                                            accumulatedDragY < -DRAG_SWAP_THRESHOLD_PX &&
                                                currentIndex > minIndex() -> {
                                                moveItem(currentIndex, currentIndex - 1, notifyParent = false)
                                                draggedIndex = currentIndex - 1
                                                accumulatedDragY = 0f
                                            }
                                        }
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!locked) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = stringResource(R.string.settings_sms_drag_hint),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = fieldLabel(field),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (!locked) {
                        Row {
                            IconButton(
                                onClick = { moveItem(index, index - 1) },
                                enabled = index > minIndex(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = stringResource(R.string.settings_sms_move_up),
                                )
                            }
                            IconButton(
                                onClick = { moveItem(index, index + 1) },
                                enabled = index < items.lastIndex,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = stringResource(R.string.settings_sms_move_down),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val DRAG_SWAP_THRESHOLD_PX = 48f

private fun fieldLabel(field: SmsField): String = when (field) {
    SmsField.SERIAL -> "سریال"
    SmsField.CITY -> "شهر"
    SmsField.KM -> "کیلومتر"
}
