package com.example.smsgateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SerialGenerator
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SerialGroupCard(
    group: SerialGenerator.GroupSummary,
    generator: SerialGenerator,
    onDelete: () -> Unit,
    onUpdated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var editing by remember(group.id) { mutableStateOf(false) }
    var errorMessage by remember(group.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var extending by remember { mutableStateOf(false) }
    var duplicateException by remember(group.id) {
        mutableStateOf<SerialGenerator.DuplicateSerialException?>(null)
    }

    var editDescription by remember(group.id) { mutableStateOf(group.description.orEmpty()) }
    var editScore by remember(group.id) { mutableStateOf(group.score.toString()) }
    var extendByCount by remember(group.id) { mutableStateOf(true) }
    var addBelow by remember(group.id) { mutableStateOf("") }
    var addAbove by remember(group.id) { mutableStateOf("") }
    var newStart by remember(group.id) { mutableStateOf("") }
    var newEnd by remember(group.id) { mutableStateOf("") }

    val createdLabel = remember(group.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(group.createdAt))
    }

    LaunchedEffect(group.description, group.score) {
        if (!editing) {
            editDescription = group.description.orEmpty()
            editScore = group.score.toString()
        }
    }

    duplicateException?.let { exception ->
        DuplicateSerialWarningDialog(
            exception = exception,
            onDismiss = { duplicateException = null },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(text = group.rangeLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.serial_group_created_at, createdLabel),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.serial_group_score, group.score),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!editing) {
                if (group.description.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.serial_group_no_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(text = group.description, style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.serial_group_total, group.total))
                Text(stringResource(R.string.serial_group_used, group.used))
                Text(stringResource(R.string.serial_group_unused, group.unused))
                Text(
                    stringResource(
                        R.string.serial_group_sale_percentage,
                        group.salePercentage.toString(),
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.serial_group_edit_button))
                    }
                    OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.serial_group_delete_button))
                    }
                }
            } else {
                OutlinedTextField(
                    value = editDescription,
                    onValueChange = { editDescription = it },
                    label = { Text(stringResource(R.string.serial_group_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = editScore,
                    onValueChange = { editScore = it.filter { ch -> ch.isDigit() }.ifEmpty { "1" } },
                    label = { Text(stringResource(R.string.serial_group_score_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = {
                        saving = true
                        errorMessage = null
                        scope.launch {
                            runCatching {
                                generator.updateGroup(
                                    group.id,
                                    SerialGenerator.UpdateGroupRequest(
                                        description = editDescription,
                                        score = editScore.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                    ),
                                )
                            }.onSuccess {
                                onUpdated()
                            }.onFailure {
                                errorMessage = it.message
                            }
                            saving = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                ) {
                    Text(
                        if (saving) {
                            stringResource(R.string.serial_group_saving)
                        } else {
                            stringResource(R.string.serial_group_save_metadata)
                        },
                    )
                }

                Text(
                    text = stringResource(R.string.serial_group_extend_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        R.string.serial_group_current_range,
                        group.startNumber,
                        group.endNumber,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { extendByCount = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.serial_group_extend_by_count))
                    }
                    OutlinedButton(
                        onClick = { extendByCount = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.serial_group_extend_by_target))
                    }
                }

                if (extendByCount) {
                    OutlinedTextField(
                        value = addBelow,
                        onValueChange = { addBelow = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.serial_group_add_below)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = addAbove,
                        onValueChange = { addAbove = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.serial_group_add_above)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                } else {
                    OutlinedTextField(
                        value = newStart,
                        onValueChange = { newStart = it.filter { ch -> ch.isDigit() } },
                        label = {
                            Text(
                                stringResource(
                                    R.string.serial_group_new_start,
                                    group.startNumber,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = newEnd,
                        onValueChange = { newEnd = it.filter { ch -> ch.isDigit() } },
                        label = {
                            Text(
                                stringResource(
                                    R.string.serial_group_new_end,
                                    group.endNumber,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Button(
                    onClick = {
                        extending = true
                        errorMessage = null
                        scope.launch {
                            runCatching {
                                val request = if (extendByCount) {
                                    SerialGenerator.ExtendRangeRequest(
                                        addBelow = addBelow.toIntOrNull()?.takeIf { it > 0 },
                                        addAbove = addAbove.toIntOrNull()?.takeIf { it > 0 },
                                    )
                                } else {
                                    SerialGenerator.ExtendRangeRequest(
                                        newStart = newStart.toIntOrNull(),
                                        newEnd = newEnd.toIntOrNull(),
                                    )
                                }
                                generator.extendGroupRange(group.id, request)
                            }.onSuccess {
                                addBelow = ""
                                addAbove = ""
                                newStart = ""
                                newEnd = ""
                                onUpdated()
                            }.onFailure { error ->
                                if (error is SerialGenerator.DuplicateSerialException) {
                                    duplicateException = error
                                } else {
                                    errorMessage = error.message
                                }
                            }
                            extending = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !extending,
                ) {
                    Text(
                        if (extending) {
                            stringResource(R.string.serial_group_extending)
                        } else {
                            stringResource(R.string.serial_group_extend_button)
                        },
                    )
                }

                errorMessage?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }

                OutlinedButton(onClick = { editing = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.serial_group_close_edit))
                }
            }
        }
    }
}
