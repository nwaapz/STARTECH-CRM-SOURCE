package com.example.smsgateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.R
import com.example.smsgateway.cloud.NullDateRepairService
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPORT_PAGE_SIZE = 50

/**
 * Simple in-app registrations report: newest first (date DESC, id DESC),
 * paged, with a one-tap repair for rows whose date is null/blank.
 */
@Composable
fun ReportsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { GatewayDatabase.getInstance(context) }

    var page by remember { mutableIntStateOf(1) }
    var total by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<SerialEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var repairFeedback by remember { mutableStateOf<String?>(null) }
    var repairFeedbackOk by remember { mutableStateOf(true) }

    val repairErrorFallback = stringResource(R.string.reports_repair_failed)

    suspend fun load(targetPage: Int) {
        loading = true
        try {
            val (rows, count) = withContext(Dispatchers.IO) {
                val dao = database.serialDao()
                // Room rejects empty IN (); placeholder unused when useGroupFilter=0
                val groupIdsPlaceholder = listOf(-1L)
                val rows = dao.listRegisteredFiltered(
                    fromDate = "",
                    toDate = "",
                    search = "",
                    phoneNorm = "",
                    filterSms = 0,
                    filterRubika = 0,
                    filterTelegram = 0,
                    filterBale = 0,
                    filterEndUser = 0,
                    filterSeller = 0,
                    useGroupFilter = 0,
                    groupIds = groupIdsPlaceholder,
                    limit = REPORT_PAGE_SIZE,
                    offset = (targetPage - 1) * REPORT_PAGE_SIZE,
                )
                val count = dao.countRegisteredFiltered(
                    fromDate = "",
                    toDate = "",
                    search = "",
                    phoneNorm = "",
                    filterSms = 0,
                    filterRubika = 0,
                    filterTelegram = 0,
                    filterBale = 0,
                    filterEndUser = 0,
                    filterSeller = 0,
                    useGroupFilter = 0,
                    groupIds = groupIdsPlaceholder,
                )
                rows to count
            }
            items = rows
            total = count
            page = targetPage
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        load(1)
    }

    val totalPages = if (total <= 0) 1 else (total + REPORT_PAGE_SIZE - 1) / REPORT_PAGE_SIZE

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.reports_total, total),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    scope.launch {
                        repairing = true
                        repairFeedback = null
                        try {
                            val result = withContext(Dispatchers.IO) {
                                NullDateRepairService.getInstance(context).repair()
                            }
                            repairFeedbackOk = result.ok
                            repairFeedback = result.message ?: repairErrorFallback
                            load(1)
                        } catch (e: Exception) {
                            repairFeedbackOk = false
                            repairFeedback = e.message ?: repairErrorFallback
                        } finally {
                            repairing = false
                        }
                    }
                },
                enabled = !repairing && !loading,
            ) {
                Text(
                    text = stringResource(
                        if (repairing) R.string.reports_repair_running else R.string.reports_repair_button,
                    ),
                )
            }
        }

        repairFeedback?.let { feedback ->
            Text(
                text = feedback,
                style = MaterialTheme.typography.bodySmall,
                color = if (repairFeedbackOk) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center),
                    )
                }
                items.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.reports_empty),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(items, key = { it.id }) { row ->
                            ReportRowCard(row)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { scope.launch { load(page - 1) } },
                enabled = !loading && !repairing && page > 1,
            ) {
                Text(stringResource(R.string.reports_prev_page))
            }
            Text(
                text = stringResource(R.string.reports_page_of, page, totalPages),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { scope.launch { load(page + 1) } },
                enabled = !loading && !repairing && page < totalPages,
            ) {
                Text(stringResource(R.string.reports_next_page))
            }
        }
    }
}

@Composable
private fun ReportRowCard(row: SerialEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = row.serial,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = row.date?.takeIf { it.isNotBlank() } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.date.isNullOrBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = PhoneUtils.formatForDisplay(row.phone) ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = listOfNotNull(
                        row.city?.takeIf { it.isNotBlank() },
                        row.km?.let { stringResource(R.string.reports_km_value, it) },
                    ).joinToString(" — ").ifEmpty { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
