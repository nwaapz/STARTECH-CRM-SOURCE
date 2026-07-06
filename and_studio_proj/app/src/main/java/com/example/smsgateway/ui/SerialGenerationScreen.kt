package com.example.smsgateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SerialGenerator
import com.example.smsgateway.data.GatewayDatabase
import kotlinx.coroutines.launch

@Composable
fun SerialGenerationScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val generator = remember { SerialGenerator(GatewayDatabase.getInstance(context)) }

    var prefix by remember { mutableStateOf("M") }
    var startNumber by remember { mutableStateOf("100") }
    var endNumber by remember { mutableStateOf("200") }
    var padWidth by remember { mutableStateOf("0") }
    var description by remember { mutableStateOf("") }
    var score by remember { mutableStateOf("1") }
    var totalCount by remember { mutableIntStateOf(0) }
    var dbTotal by remember { mutableIntStateOf(0) }
    var dbUnused by remember { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf<List<SerialGenerator.GroupSummary>>(emptyList()) }
    var isGenerating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SerialGenerator.GroupSummary?>(null) }
    var duplicateException by remember { mutableStateOf<SerialGenerator.DuplicateSerialException?>(null) }

    fun buildRequest(): SerialGenerator.GenerateRequest {
        return SerialGenerator.GenerateRequest(
            prefix = prefix,
            start = startNumber.toInt(),
            end = endNumber.toInt(),
            padWidth = padWidth.toInt(),
            description = description.ifBlank { null },
            score = score.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        )
    }

    fun refreshStats() {
        scope.launch {
            val (total, unused) = generator.getStats()
            dbTotal = total
            dbUnused = unused
        }
    }

    fun refreshGroups() {
        scope.launch {
            groups = generator.listGroups()
        }
    }

    fun updatePreview() {
        errorMessage = null
        totalCount = runCatching {
            generator.validate(buildRequest())
        }.getOrElse {
            errorMessage = it.message
            0
        }
    }

    LaunchedEffect(Unit) {
        refreshStats()
        refreshGroups()
        updatePreview()
    }

    fun startGeneration() {
        val request = buildRequest()

        isGenerating = true
        progress = 0f
        resultMessage = null
        errorMessage = null

        scope.launch {
            try {
                val result = generator.generate(request) { processed, total ->
                    progress = if (total > 0) processed.toFloat() / total else 0f
                }
                resultMessage = context.getString(
                    R.string.serial_generation_result,
                    result.inserted,
                    result.skipped,
                    result.total,
                )
                refreshStats()
                refreshGroups()
            } catch (error: SerialGenerator.DuplicateSerialException) {
                duplicateException = error
            } catch (error: Exception) {
                errorMessage = error.message
            } finally {
                isGenerating = false
            }
        }
    }

    duplicateException?.let { exception ->
        DuplicateSerialWarningDialog(
            exception = exception,
            onDismiss = { duplicateException = null },
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.serial_generation_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.serial_generation_confirm_message,
                        totalCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        startGeneration()
                    },
                ) {
                    Text(stringResource(R.string.serial_generation_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.serial_generation_confirm_no))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.serial_group_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.serial_group_delete_message,
                        target.rangeLabel,
                        target.total,
                        target.used,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            generator.deleteGroup(target.id)
                            deleteTarget = null
                            refreshStats()
                            refreshGroups()
                        }
                    },
                ) {
                    Text(stringResource(R.string.serial_group_delete_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.serial_group_delete_no))
                }
            },
        )
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
                    text = stringResource(R.string.serial_db_stats),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.serial_db_total, dbTotal))
                Text(stringResource(R.string.serial_db_unused, dbUnused))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.serial_generation_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.serial_generation_hint),
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedTextField(
                    value = prefix,
                    onValueChange = {
                        prefix = it
                        updatePreview()
                    },
                    label = { Text(stringResource(R.string.serial_prefix_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isGenerating,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startNumber,
                        onValueChange = {
                            startNumber = it.filter { ch -> ch.isDigit() }
                            updatePreview()
                        },
                        label = { Text(stringResource(R.string.serial_start_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isGenerating,
                    )
                    OutlinedTextField(
                        value = endNumber,
                        onValueChange = {
                            endNumber = it.filter { ch -> ch.isDigit() }
                            updatePreview()
                        },
                        label = { Text(stringResource(R.string.serial_end_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isGenerating,
                    )
                }
                OutlinedTextField(
                    value = padWidth,
                    onValueChange = {
                        padWidth = it.filter { ch -> ch.isDigit() }
                        updatePreview()
                    },
                    label = { Text(stringResource(R.string.serial_pad_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isGenerating,
                )
                OutlinedTextField(
                    value = score,
                    onValueChange = {
                        score = it.filter { ch -> ch.isDigit() }.ifEmpty { "1" }
                        updatePreview()
                    },
                    label = { Text(stringResource(R.string.serial_group_score_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isGenerating,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.serial_group_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    enabled = !isGenerating,
                )

                Text(
                    text = if (totalCount > 0) {
                        stringResource(R.string.serial_preview_count, totalCount)
                    } else {
                        stringResource(R.string.serial_preview_invalid)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                errorMessage?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }

                if (isGenerating) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Button(
                    onClick = {
                        runCatching {
                            val count = generator.validate(buildRequest())
                            if (count > SerialGenerator.LARGE_BATCH_THRESHOLD) {
                                showConfirmDialog = true
                            } else {
                                startGeneration()
                            }
                        }.onFailure { error ->
                            errorMessage = error.message
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating && totalCount > 0,
                ) {
                    Text(stringResource(R.string.serial_generate_button))
                }

                resultMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Text(
            text = stringResource(R.string.serial_groups_title),
            style = MaterialTheme.typography.titleMedium,
        )

        if (groups.isEmpty()) {
            Text(
                text = stringResource(R.string.serial_groups_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            groups.forEach { group ->
                SerialGroupCard(
                    group = group,
                    generator = generator,
                    onDelete = { deleteTarget = group },
                    onUpdated = {
                        refreshGroups()
                        refreshStats()
                    },
                )
            }
        }
    }
}
