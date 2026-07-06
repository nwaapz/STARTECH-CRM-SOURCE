package com.example.smsgateway.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SerialGenerator

@Composable
fun DuplicateSerialWarningDialog(
    exception: SerialGenerator.DuplicateSerialException,
    onDismiss: () -> Unit,
) {
    val scrollable = exception.duplicateRanges.size > 3
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duplicate_serial_title)) },
        text = {
            Column {
                Text(
                    text = exception.message
                        ?: stringResource(R.string.duplicate_serial_summary, exception.duplicateCount),
                )
                Column(
                    modifier = if (scrollable) {
                        Modifier
                            .heightIn(max = 84.dp)
                            .verticalScroll(scrollState)
                    } else {
                        Modifier
                    },
                ) {
                    exception.duplicateRanges.forEach { range ->
                        Text(
                            text = stringResource(
                                R.string.duplicate_serial_range_line,
                                range.rangeLabel,
                                range.count,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.duplicate_serial_confirm))
            }
        },
    )
}
