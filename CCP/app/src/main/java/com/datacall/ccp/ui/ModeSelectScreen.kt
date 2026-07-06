package com.datacall.ccp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.datacall.ccp.data.AppPreferences

@Composable
fun ModeSelectScreen(
    preferences: AppPreferences,
    onSender: () -> Unit,
    onReceiver: () -> Unit,
    onDiagnostics: () -> Unit
) {
    LaunchedEffect(Unit) {
        preferences.setLastMode("home")
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Data Call", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Send images over phone calls without internet",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onSender, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Sender Mode")
        }
        Button(onClick = onReceiver, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Receiver Mode")
        }
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Diagnostics / Loopback")
        }
    }
}
