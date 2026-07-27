package com.payamesh.licenseadmin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.payamesh.licenseadmin.data.LicenseAdminDatabase
import com.payamesh.licenseadmin.data.LicenseCheckLogEntity
import com.payamesh.licenseadmin.data.LicenseSeatEntity
import com.payamesh.licenseadmin.ui.theme.LicenseAdminTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LicenseAdminTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LicenseAdminApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseAdminApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seats by remember { mutableStateOf<List<LicenseSeatEntity>>(emptyList()) }
    var logs by remember { mutableStateOf<List<LicenseCheckLogEntity>>(emptyList()) }
    var phoneDraft by remember { mutableStateOf("") }
    var noteDraft by remember { mutableStateOf("") }
    var transferFrom by remember { mutableStateOf<LicenseSeatEntity?>(null) }
    var transferToDraft by remember { mutableStateOf("") }
    var serviceRunning by remember {
        mutableStateOf(LicenseAdminForegroundService.isRunning(context))
    }
    var smsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    fun refresh() {
        scope.launch {
            val dao = LicenseAdminDatabase.getInstance(context).licenseSeatDao()
            seats = dao.listAll()
            logs = dao.recentLogs(40)
            serviceRunning = LicenseAdminForegroundService.isRunning(context)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(5_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (serviceRunning) {
                                stringResource(R.string.service_running)
                            } else {
                                stringResource(R.string.service_stopped)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val perms = buildList {
                                        add(Manifest.permission.RECEIVE_SMS)
                                        add(Manifest.permission.SEND_SMS)
                                        add(Manifest.permission.READ_SMS)
                                        add(Manifest.permission.READ_PHONE_STATE)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    permissionLauncher.launch(perms.toTypedArray())
                                },
                            ) {
                                Text(stringResource(R.string.request_permissions))
                            }
                            Button(
                                onClick = {
                                    if (serviceRunning) {
                                        LicenseAdminForegroundService.stop(context)
                                    } else if (smsGranted) {
                                        LicenseAdminForegroundService.start(context)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.request_permissions),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    serviceRunning = LicenseAdminForegroundService.isRunning(context)
                                },
                            ) {
                                Text(
                                    if (serviceRunning) {
                                        stringResource(R.string.stop_service)
                                    } else {
                                        stringResource(R.string.start_service)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.seats_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = phoneDraft,
                            onValueChange = { phoneDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.phone_hint)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = noteDraft,
                            onValueChange = { noteDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.note_hint)) },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        LicenseAutoResponder.addSeat(
                                            context,
                                            phoneDraft,
                                            noteDraft.takeIf { it.isNotBlank() },
                                        )
                                        phoneDraft = ""
                                        noteDraft = ""
                                        refresh()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.added),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } catch (error: Exception) {
                                        Toast.makeText(
                                            context,
                                            error.message ?: "خطا",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.add_seat))
                        }
                    }
                }
            }
            if (seats.isEmpty()) {
                item { Text(stringResource(R.string.empty_seats)) }
            }
            items(seats, key = { it.id }) { seat ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = PhoneUtils.formatForDisplay(seat.msisdn) ?: seat.msisdn,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (seat.revoked) {
                                stringResource(R.string.revoked)
                            } else {
                                stringResource(R.string.active)
                            },
                            color = if (seat.revoked) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        seat.note?.takeIf { it.isNotBlank() }?.let {
                            Text(text = it, style = MaterialTheme.typography.bodySmall)
                        }
                        seat.previousMsisdn?.let {
                            Text(
                                text = "قبلی: ${PhoneUtils.formatForDisplay(it) ?: it}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!seat.revoked) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                LicenseAutoResponder.revokeSeat(context, seat.msisdn)
                                                refresh()
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.revoked_ok),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } catch (error: Exception) {
                                                Toast.makeText(
                                                    context,
                                                    error.message ?: "خطا",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.revoke))
                                }
                                OutlinedButton(
                                    onClick = {
                                        transferFrom = seat
                                        transferToDraft = ""
                                    },
                                ) {
                                    Text(stringResource(R.string.transfer))
                                }
                            }
                        }
                        if (transferFrom?.id == seat.id) {
                            OutlinedTextField(
                                value = transferToDraft,
                                onValueChange = { transferToDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.transfer_to)) },
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            LicenseAutoResponder.transferSeat(
                                                context,
                                                seat.msisdn,
                                                transferToDraft,
                                                seat.note,
                                            )
                                            transferFrom = null
                                            refresh()
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.transferred),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } catch (error: Exception) {
                                            Toast.makeText(
                                                context,
                                                error.message ?: "خطا",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.transfer))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.logs_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(logs, key = { it.id }) { log ->
                Text(
                    text = "${PhoneUtils.formatForDisplay(log.senderMsisdn) ?: log.senderMsisdn} → ${log.result}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
