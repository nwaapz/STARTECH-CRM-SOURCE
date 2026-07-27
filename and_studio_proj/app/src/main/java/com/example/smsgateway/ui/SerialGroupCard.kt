package com.example.smsgateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SerialGenerator
import com.example.smsgateway.SerialGroupCategory
import com.example.smsgateway.SerialGroupExcelExporter
import com.example.smsgateway.SerialGroupWarranty
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember(group.id) { mutableStateOf(false) }
    var errorMessage by remember(group.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var extending by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var duplicateException by remember(group.id) {
        mutableStateOf<SerialGenerator.DuplicateSerialException?>(null)
    }

    var editDescription by remember(group.id) { mutableStateOf(group.description.orEmpty()) }
    var editScore by remember(group.id) { mutableStateOf(group.score.toString()) }
    var editCategory by remember(group.id) {
        mutableStateOf(SerialGroupCategory.normalizeOrDefault(group.category))
    }
    var editWarrantyMonths by remember(group.id) {
        mutableStateOf(group.warrantyMonths?.toString().orEmpty())
    }
    var editWarrantyKm by remember(group.id) {
        mutableStateOf(group.warrantyKm?.toString().orEmpty())
    }
    var editImageVerificationRequired by remember(group.id) {
        mutableStateOf(group.imageVerificationRequired)
    }
    var extendByCount by remember(group.id) { mutableStateOf(true) }
    var addBelow by remember(group.id) { mutableStateOf("") }
    var addAbove by remember(group.id) { mutableStateOf("") }
    var newStart by remember(group.id) { mutableStateOf("") }
    var newEnd by remember(group.id) { mutableStateOf("") }

    val createdLabel = remember(group.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(group.createdAt))
    }

    val categoryLabel = stringResource(
        if (group.category == SerialGroupCategory.SELLER_TO_END_USER) {
            R.string.serial_group_category_seller
        } else {
            R.string.serial_group_category_end_user
        },
    )

    LaunchedEffect(
        group.description,
        group.score,
        group.category,
        group.warrantyMonths,
        group.warrantyKm,
        group.imageVerificationRequired,
    ) {
        if (!editing) {
            editDescription = group.description.orEmpty()
            editScore = group.score.toString()
            editCategory = SerialGroupCategory.normalizeOrDefault(group.category)
            editWarrantyMonths = group.warrantyMonths?.toString().orEmpty()
            editWarrantyKm = group.warrantyKm?.toString().orEmpty()
            editImageVerificationRequired = group.imageVerificationRequired
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

            Text(
                text = stringResource(R.string.serial_group_category_value, categoryLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.serial_group_warranty_value,
                    SerialGroupWarranty.formatFa(group.warrantyMonths, group.warrantyKm) ?: "—",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    if (group.imageVerificationRequired) {
                        R.string.serial_group_image_verify_on
                    } else {
                        R.string.serial_group_image_verify_off
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )

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
                    OutlinedButton(
                        onClick = {
                            exporting = true
                            errorMessage = null
                            scope.launch {
                                runCatching {
                                    val serials = generator.listSerialsForGroup(group.id)
                                    if (serials.isEmpty()) {
                                        errorMessage = context.getString(R.string.serial_group_export_empty)
                                    } else {
                                        SerialGroupExcelExporter.shareExcel(
                                            context = context,
                                            serials = serials,
                                            filenameBase = group.rangeLabel,
                                        )
                                    }
                                }.onFailure {
                                    errorMessage = it.message
                                }
                                exporting = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !exporting && group.total > 0,
                    ) {
                        Text(
                            if (exporting) {
                                stringResource(R.string.serial_group_exporting)
                            } else {
                                stringResource(R.string.serial_group_export_excel)
                            },
                        )
                    }
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.serial_group_delete_button))
                }
                errorMessage?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
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
                Text(
                    text = stringResource(R.string.serial_group_category_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = editCategory == SerialGroupCategory.END_USER_CLIENT,
                        onClick = { editCategory = SerialGroupCategory.END_USER_CLIENT },
                        label = { Text(stringResource(R.string.serial_group_category_end_user)) },
                    )
                    FilterChip(
                        selected = editCategory == SerialGroupCategory.SELLER_TO_END_USER,
                        onClick = { editCategory = SerialGroupCategory.SELLER_TO_END_USER },
                        label = { Text(stringResource(R.string.serial_group_category_seller)) },
                    )
                }
                OutlinedTextField(
                    value = editWarrantyMonths,
                    onValueChange = { editWarrantyMonths = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.serial_group_warranty_months_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = editWarrantyKm,
                    onValueChange = { editWarrantyKm = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.serial_group_warranty_km_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    text = stringResource(R.string.serial_group_warranty_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = if (editImageVerificationRequired) 2.dp else 1.dp,
                    color = if (editImageVerificationRequired) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.serial_group_image_verify_label),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.serial_group_image_verify_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = editImageVerificationRequired,
                            onCheckedChange = { editImageVerificationRequired = it },
                        )
                    }
                }
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
                                        category = editCategory,
                                        warrantyMonths = editWarrantyMonths.toIntOrNull()?.takeIf { it > 0 },
                                        warrantyKm = editWarrantyKm.toIntOrNull()?.takeIf { it > 0 },
                                        imageVerificationRequired = editImageVerificationRequired,
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
