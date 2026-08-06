package com.example.smsgateway.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.R
import com.example.smsgateway.SerialGenerator
import com.example.smsgateway.SerialGroupCategory
import com.example.smsgateway.SerialStateCsv
import com.example.smsgateway.data.GatewayDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

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
    var category by remember { mutableStateOf(SerialGroupCategory.DEFAULT) }
    var warrantyMonths by remember { mutableStateOf("6") }
    var warrantyKm by remember { mutableStateOf("") }
    var imageVerificationRequired by remember { mutableStateOf(false) }
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
    var showOrphanClearDialog by remember { mutableStateOf(false) }
    var orphanPreview by remember { mutableStateOf<SerialGenerator.OrphanCleanupPreview?>(null) }
    var orphanClearing by remember { mutableStateOf(false) }
    var orphanResultMessage by remember { mutableStateOf<String?>(null) }

    var csvUri by remember { mutableStateOf<Uri?>(null) }
    var csvFileName by remember { mutableStateOf<String?>(null) }
    var csvParseResult by remember { mutableStateOf<SerialStateCsv.ParseResult?>(null) }
    var csvParsing by remember { mutableStateOf(false) }
    var csvImporting by remember { mutableStateOf(false) }
    var csvProgress by remember { mutableStateOf(0f) }
    var csvError by remember { mutableStateOf<String?>(null) }
    var csvResult by remember { mutableStateOf<String?>(null) }
    var showCsvConfirmDialog by remember { mutableStateOf(false) }

    fun buildRequest(): SerialGenerator.GenerateRequest {
        return SerialGenerator.GenerateRequest(
            prefix = prefix,
            start = startNumber.toInt(),
            end = endNumber.toInt(),
            padWidth = padWidth.toInt(),
            description = description.ifBlank { null },
            score = score.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            category = category,
            warrantyMonths = warrantyMonths.toIntOrNull()?.takeIf { it > 0 },
            warrantyKm = warrantyKm.toIntOrNull()?.takeIf { it > 0 },
            imageVerificationRequired = imageVerificationRequired,
        )
    }

    fun buildCsvMetaRequest(): SerialGenerator.ImportStateMetaRequest {
        return SerialGenerator.ImportStateMetaRequest(
            description = description.ifBlank { null },
            score = score.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            category = category,
            warrantyMonths = warrantyMonths.toIntOrNull()?.takeIf { it > 0 },
            warrantyKm = warrantyKm.toIntOrNull()?.takeIf { it > 0 },
            imageVerificationRequired = imageVerificationRequired,
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

    fun clearCsvSelection() {
        csvUri = null
        csvFileName = null
        csvParseResult = null
        csvError = null
        csvResult = null
        csvProgress = 0f
    }

    fun parseCsvUri(uri: Uri, displayName: String?) {
        csvParsing = true
        csvError = null
        csvResult = null
        csvParseResult = null
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                            SerialStateCsv.parse(reader)
                        }
                    } ?: throw SerialStateCsv.CsvFormatException("Cannot open CSV file")
                }
                csvUri = uri
                csvFileName = displayName ?: uri.lastPathSegment ?: "import.csv"
                csvParseResult = parsed
            } catch (error: Exception) {
                clearCsvSelection()
                csvError = error.message
            } finally {
                csvParsing = false
            }
        }
    }

    val csvPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            }
        }.getOrNull()
        parseCsvUri(uri, name)
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

    fun startCsvImport() {
        val parsed = csvParseResult ?: return
        // Drop Compose-held parse map ASAP; local [parsed] still used for insert.
        csvParseResult = null
        csvImporting = true
        csvProgress = 0f
        csvError = null
        csvResult = null
        scope.launch {
            try {
                val result = generator.generateImportWithState(buildCsvMetaRequest(), parsed) { p ->
                    csvProgress = p
                }
                val message = context.getString(
                    R.string.serial_csv_import_result,
                    result.inserted,
                    result.skipped,
                    result.usedInserted,
                    result.unusedInserted,
                    result.invalidCount,
                )
                csvUri = null
                csvFileName = null
                csvResult = message
                refreshStats()
                refreshGroups()
            } catch (error: Exception) {
                csvError = error.message
            } finally {
                csvImporting = false
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

    if (showCsvConfirmDialog) {
        val parsed = csvParseResult
        val usedPreview = parsed?.rowsBySerial?.values?.count { PhoneUtils.isRegisteredPhone(it.phone) } ?: 0
        val unusedPreview = (parsed?.rowsBySerial?.size ?: 0) - usedPreview
        AlertDialog(
            onDismissRequest = { showCsvConfirmDialog = false },
            title = { Text(stringResource(R.string.serial_csv_import_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.serial_csv_import_confirm_message,
                        parsed?.rowsBySerial?.size ?: 0,
                        usedPreview,
                        unusedPreview,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCsvConfirmDialog = false
                        startCsvImport()
                    },
                ) {
                    Text(stringResource(R.string.serial_csv_import_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCsvConfirmDialog = false }) {
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

    if (showOrphanClearDialog) {
        val preview = orphanPreview
        AlertDialog(
            onDismissRequest = {
                if (!orphanClearing) {
                    showOrphanClearDialog = false
                    orphanPreview = null
                }
            },
            title = { Text(stringResource(R.string.serial_orphan_clear_title)) },
            text = {
                if (preview == null) {
                    Text(stringResource(R.string.serial_orphan_clear_working))
                } else {
                    Text(
                        stringResource(
                            R.string.serial_orphan_clear_message,
                            preview.total,
                            preview.registered,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (preview == null || orphanClearing) return@TextButton
                        orphanClearing = true
                        scope.launch {
                            val result = generator.clearOrphanSerials()
                            orphanClearing = false
                            showOrphanClearDialog = false
                            orphanPreview = null
                            orphanResultMessage = context.getString(
                                R.string.serial_orphan_clear_result,
                                result.deleted,
                            )
                            refreshStats()
                            refreshGroups()
                        }
                    },
                    enabled = preview != null && !orphanClearing,
                ) {
                    Text(stringResource(R.string.serial_orphan_clear_yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOrphanClearDialog = false
                        orphanPreview = null
                    },
                    enabled = !orphanClearing,
                ) {
                    Text(stringResource(R.string.serial_group_delete_no))
                }
            },
        )
    }

    val busy = isGenerating || csvImporting || csvParsing || orphanClearing

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
                    text = stringResource(R.string.serial_generation_typo_safe_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

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
                    text = stringResource(R.string.serial_orphan_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        orphanResultMessage = null
                        orphanClearing = true
                        scope.launch {
                            val preview = generator.orphanCleanupPreview()
                            orphanClearing = false
                            if (preview.total <= 0) {
                                orphanResultMessage =
                                    context.getString(R.string.serial_orphan_clear_none)
                            } else {
                                orphanPreview = preview
                                showOrphanClearDialog = true
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.serial_orphan_clear_button))
                }
                orphanResultMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
                    enabled = !busy,
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
                        enabled = !busy,
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
                        enabled = !busy,
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
                    enabled = !busy,
                )
                GroupMetaFields(
                    score = score,
                    onScoreChange = {
                        score = it
                        updatePreview()
                    },
                    category = category,
                    onCategoryChange = { category = it },
                    warrantyMonths = warrantyMonths,
                    onWarrantyMonthsChange = {
                        warrantyMonths = it
                        updatePreview()
                    },
                    warrantyKm = warrantyKm,
                    onWarrantyKmChange = {
                        warrantyKm = it
                        updatePreview()
                    },
                    imageVerificationRequired = imageVerificationRequired,
                    onImageVerificationChange = { imageVerificationRequired = it },
                    description = description,
                    onDescriptionChange = { description = it },
                    enabled = !busy,
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
                            if (count > SerialGenerator.CONFIRM_BATCH_THRESHOLD) {
                                showConfirmDialog = true
                            } else {
                                startGeneration()
                            }
                        }.onFailure { error ->
                            errorMessage = error.message
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && totalCount > 0,
                ) {
                    Text(stringResource(R.string.serial_generate_button))
                }

                resultMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.serial_csv_import_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.serial_csv_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.serial_csv_import_columns),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.serial_csv_import_sample),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.serial_csv_import_max,
                        SerialStateCsv.MAX_ROWS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedButton(
                    onClick = {
                        csvPicker.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.serial_csv_import_pick))
                }

                if (csvParsing) {
                    Text(stringResource(R.string.serial_csv_import_parsing))
                }

                csvFileName?.let { name ->
                    Text(
                        stringResource(
                            R.string.serial_csv_import_file,
                            name,
                            csvParseResult?.rowsBySerial?.size ?: 0,
                        ),
                    )
                }

                csvParseResult?.stats?.invalidSamples?.takeIf { it.isNotEmpty() }?.let { samples ->
                    Text(
                        text = samples.joinToString("، "),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (csvImporting) {
                    LinearProgressIndicator(
                        progress = { csvProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                csvError?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                csvResult?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val parsed = csvParseResult
                            if (parsed == null) {
                                csvError = context.getString(R.string.serial_csv_import_no_file)
                                return@Button
                            }
                            runCatching {
                                generator.validateImportMetaOnly(buildCsvMetaRequest())
                            }.onFailure {
                                csvError = it.message
                                return@Button
                            }
                            if (parsed.rowsBySerial.size > SerialGenerator.CONFIRM_BATCH_THRESHOLD) {
                                showCsvConfirmDialog = true
                            } else {
                                startCsvImport()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && csvParseResult != null,
                    ) {
                        Text(stringResource(R.string.serial_csv_import_button))
                    }
                    OutlinedButton(
                        onClick = { clearCsvSelection() },
                        enabled = !busy && (csvFileName != null || csvResult != null),
                    ) {
                        Text(stringResource(R.string.serial_csv_import_clear))
                    }
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

@Composable
private fun GroupMetaFields(
    score: String,
    onScoreChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    warrantyMonths: String,
    onWarrantyMonthsChange: (String) -> Unit,
    warrantyKm: String,
    onWarrantyKmChange: (String) -> Unit,
    imageVerificationRequired: Boolean,
    onImageVerificationChange: (Boolean) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = score,
        onValueChange = { onScoreChange(it.filter { ch -> ch.isDigit() }.ifEmpty { "1" }) },
        label = { Text(stringResource(R.string.serial_group_score_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
    )
    Text(
        text = stringResource(R.string.serial_group_category_label),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = category == SerialGroupCategory.END_USER_CLIENT,
            onClick = { onCategoryChange(SerialGroupCategory.END_USER_CLIENT) },
            label = { Text(stringResource(R.string.serial_group_category_end_user)) },
            enabled = enabled,
        )
        FilterChip(
            selected = category == SerialGroupCategory.SELLER_TO_END_USER,
            onClick = { onCategoryChange(SerialGroupCategory.SELLER_TO_END_USER) },
            label = { Text(stringResource(R.string.serial_group_category_seller)) },
            enabled = enabled,
        )
    }
    OutlinedTextField(
        value = warrantyMonths,
        onValueChange = { onWarrantyMonthsChange(it.filter { ch -> ch.isDigit() }) },
        label = { Text(stringResource(R.string.serial_group_warranty_months_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
    )
    OutlinedTextField(
        value = warrantyKm,
        onValueChange = { onWarrantyKmChange(it.filter { ch -> ch.isDigit() }) },
        label = { Text(stringResource(R.string.serial_group_warranty_km_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
    )
    Text(
        text = stringResource(R.string.serial_group_warranty_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (imageVerificationRequired) 2.dp else 1.dp,
        color = if (imageVerificationRequired) {
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
                checked = imageVerificationRequired,
                onCheckedChange = onImageVerificationChange,
                enabled = enabled,
            )
        }
    }
    OutlinedTextField(
        value = description,
        onValueChange = onDescriptionChange,
        label = { Text(stringResource(R.string.serial_group_description_label)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        enabled = enabled,
    )
}
