package com.datacall.ccp.ui.receiver

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.datacall.ccp.viewmodel.ReceiverViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(
    viewModel: ReceiverViewModel,
    onBack: () -> Unit,
    onManageEndpoints: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.syncFromCallState()
            viewModel.refreshReceivedImages()
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receiver") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.isListening) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        "Listening — any incoming call will be treated as a data call",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (state.incomingCallPending) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Incoming call — answer to start receiving image data",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (!state.isListening) {
                Button(onClick = { viewModel.startListening() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start listening")
                }
            } else {
                OutlinedButton(onClick = { viewModel.stopListening() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isReceiving) "Stop listening" else "Stop listening")
                }
            }

            Text(
                "Tips: tap Start listening BEFORE the call. Use speakerphone on both phones, quiet room, do not hold phone to ear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "While listening, answer any phone call and the app will automatically receive image data over the call.",
                style = MaterialTheme.typography.bodySmall
            )

            if (state.isReceiving) {
                Text("Receiving data...", color = MaterialTheme.colorScheme.primary)
            }

            OutlinedButton(onClick = onManageEndpoints, modifier = Modifier.fillMaxWidth()) {
                Text("Manage sender numbers (optional labels)")
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("Received images", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.receivedImages) { file ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(file.name, style = MaterialTheme.typography.bodySmall)
                            Image(
                                painter = rememberAsyncImagePainter(file),
                                contentDescription = file.name,
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }
    }
}
