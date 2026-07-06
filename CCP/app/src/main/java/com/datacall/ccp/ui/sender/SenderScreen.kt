package com.datacall.ccp.ui.sender

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import com.datacall.ccp.viewmodel.SenderViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(viewModel: SenderViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.compressFromUri(it) }
    }

    val cameraFile = remember { File(context.filesDir, "camera/capture.jpg").apply { parentFile?.mkdirs() } }
    val cameraUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cameraFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) viewModel.compressFromFile(cameraFile)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val callGranted = grants[Manifest.permission.CALL_PHONE] == true
        viewModel.send(callGranted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sender") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = state.recipientPhone,
                    onValueChange = { viewModel.setRecipient(it) },
                    label = { Text("Recipient phone") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.endpoints.forEach { ep ->
                        DropdownMenuItem(
                            text = { Text("${ep.label} (${ep.phone})") },
                            onClick = {
                                viewModel.setRecipient(ep.phone)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Text(
                "Preview plays a short ~3s sample buzz to check your speaker. It is NOT the full transfer length.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = { viewModel.previewDataTone() },
                enabled = !state.isPreviewingTone && !state.isSending,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isPreviewingTone) "Playing ~3s sample..." else "Preview data tone (~3s)")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { cameraLauncher.launch(cameraUri) }, modifier = Modifier.weight(1f)) {
                    Text("Camera")
                }
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Text("Gallery")
                }
            }

            if (state.isCompressing) {
                CircularProgressIndicator()
                Text("Compressing...")
            }

            state.compressionResult?.let { result ->
                Image(
                    painter = rememberAsyncImagePainter(result.file),
                    contentDescription = "Compressed preview",
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentScale = ContentScale.Fit
                )
                Text("Saved: ${result.file.name}")
                Text("Original: ${result.originalSizeBytes / 1024} KB → Compressed: ${result.compressedSizeBytes / 1024} KB")
                Text("Size: ${result.width}×${result.height}, quality ${result.quality}%")
                val etaSec = (result.compressedSizeBytes * 8 + 2000) / 600
                Text(
                    "Est. full transfer: ~${etaSec}s for this image (handshake + all packets at 600 bps)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            state.logs.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            state.progress?.let { p ->
                Text("Status: ${p.message.ifBlank { p.phase.name }}")
                if (p.totalPackets > 0) Text("Packet ${p.currentPacket}/${p.totalPackets}")
            }

            state.metrics?.let { m ->
                Text("Done in ${m.durationMs / 1000}s, ${m.effectiveBitrate.toInt()} bps effective")
                if (m.packetsRetried > 0) Text("Retries: ${m.packetsRetried}")
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    val callGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CALL_PHONE
                    ) == PackageManager.PERMISSION_GRANTED
                    if (callGranted) {
                        viewModel.send(true)
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.READ_PHONE_STATE
                            )
                        )
                    }
                },
                enabled = !state.isSending && state.compressionResult != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isSending) "Sending..." else "Send (auto-dial)")
            }
        }
    }
}
