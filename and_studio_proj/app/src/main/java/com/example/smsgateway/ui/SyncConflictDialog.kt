package com.example.smsgateway.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.cloud.SyncConflictChoice
import com.example.smsgateway.cloud.SyncConflictItem
import com.example.smsgateway.cloud.SyncConflictSession
import org.json.JSONObject

@Composable
fun SyncConflictDialog(
    session: SyncConflictSession,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, SyncConflictChoice>) -> Unit,
) {
    val choices = remember(session) {
        mutableStateMapOf<String, SyncConflictChoice>().apply {
            session.conflicts.forEach { put(it.serial, SyncConflictChoice.LOCAL) }
        }
    }
    val allChosen = session.conflicts.all { choices.containsKey(it.serial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cloud_sync_conflict_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                session.conflicts.forEach { item ->
                    ConflictRow(
                        item = item,
                        choice = choices[item.serial] ?: SyncConflictChoice.LOCAL,
                        onChoice = { choices[item.serial] = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = allChosen,
                onClick = { onConfirm(choices.toMap()) },
            ) {
                Text(stringResource(R.string.settings_cloud_sync_conflict_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cloud_sync_conflict_cancel))
            }
        },
    )
}

@Composable
private fun ConflictRow(
    item: SyncConflictItem,
    choice: SyncConflictChoice,
    onChoice: (SyncConflictChoice) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${stringResource(R.string.settings_cloud_sync_field_serial)}: ${item.serial}",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SideCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.settings_cloud_sync_conflict_local),
                payload = item.local.payload,
                selected = choice == SyncConflictChoice.LOCAL,
                onSelect = { onChoice(SyncConflictChoice.LOCAL) },
            )
            SideCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.settings_cloud_sync_conflict_remote),
                payload = item.remote.payload,
                selected = choice == SyncConflictChoice.REMOTE,
                onSelect = { onChoice(SyncConflictChoice.REMOTE) },
            )
        }
    }
}

@Composable
private fun SideCard(
    modifier: Modifier,
    title: String,
    payload: JSONObject,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        modifier = modifier
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = MaterialTheme.shapes.small,
            )
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(4.dp))
        FieldLine(R.string.settings_cloud_sync_field_phone, payload.optString("phone"))
        FieldLine(R.string.settings_cloud_sync_field_city, payload.optString("city"))
        FieldLine(
            R.string.settings_cloud_sync_field_km,
            if (payload.has("km") && !payload.isNull("km")) payload.optInt("km").toString() else "—",
        )
        FieldLine(
            R.string.settings_cloud_sync_field_date,
            payload.optString("dateJalali").ifBlank { payload.optString("date") },
        )
        FieldLine(R.string.settings_cloud_sync_field_source, payload.optString("regSource"))
    }
}

@Composable
private fun FieldLine(labelRes: Int, value: String) {
    val shown = value.trim().let { if (it.isEmpty() || it == "null") "—" else it }
    Text(
        text = "${stringResource(labelRes)}: $shown",
        style = MaterialTheme.typography.bodySmall,
    )
}
