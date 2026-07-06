package com.datacall.ccp.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.datacall.ccp.viewmodel.EndpointViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndpointListScreen(viewModel: EndpointViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trusted senders") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Add phone numbers allowed to send data when they call.")
            OutlinedTextField(
                value = state.phoneInput,
                onValueChange = viewModel::setPhone,
                label = { Text("Phone number") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.labelInput,
                onValueChange = viewModel::setLabel,
                label = { Text("Label") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { viewModel.addEndpoint() }, modifier = Modifier.fillMaxWidth()) {
                Text("Add sender")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.endpoints) { ep ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(ep.label, style = MaterialTheme.typography.titleSmall)
                        Text(ep.phone)
                        TextButton(onClick = { viewModel.deleteEndpoint(ep) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}
