package com.datacall.ccp.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.datacall.ccp.call.CallState
import com.datacall.ccp.call.CallStateMonitor
import com.datacall.ccp.call.OutgoingCallLauncher
import com.datacall.ccp.data.EndpointEntity
import com.datacall.ccp.data.EndpointRepository
import com.datacall.ccp.image.CompressionResult
import com.datacall.ccp.image.ImageCompressor
import com.datacall.ccp.modem.FskConfig
import com.datacall.ccp.session.TransferMetrics
import com.datacall.ccp.session.TransferProgress
import com.datacall.ccp.session.TransferSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class SenderUiState(
    val endpoints: List<EndpointEntity> = emptyList(),
    val recipientPhone: String = "",
    val compressionResult: CompressionResult? = null,
    val isCompressing: Boolean = false,
    val isSending: Boolean = false,
    val isPreviewingTone: Boolean = false,
    val progress: TransferProgress? = null,
    val metrics: TransferMetrics? = null,
    val logs: List<String> = emptyList(),
    val error: String? = null
)

class SenderViewModel(
    app: Application,
    private val endpointRepository: EndpointRepository
) : AndroidViewModel(app) {
    private val compressor = ImageCompressor(app)
    private val callMonitor = CallStateMonitor(app)
    private var sendJob: Job? = null
    private var previewJob: Job? = null

    private val _state = MutableStateFlow(SenderUiState())
    val state: StateFlow<SenderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            endpointRepository.observeAll().collect { list ->
                _state.value = _state.value.copy(endpoints = list)
            }
        }
    }

    fun setRecipient(phone: String) {
        _state.value = _state.value.copy(recipientPhone = phone, error = null)
    }

    fun compressFromUri(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCompressing = true, error = null)
            try {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() } ?: throw IllegalStateException("Cannot read image")
                val result = compressor.compressFromBytes(bytes)
                _state.value = _state.value.copy(compressionResult = result, isCompressing = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isCompressing = false, error = e.message)
            }
        }
    }

    fun compressFromFile(file: File) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCompressing = true, error = null)
            try {
                val result = compressor.compressFromFile(file)
                _state.value = _state.value.copy(compressionResult = result, isCompressing = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isCompressing = false, error = e.message)
            }
        }
    }

    fun previewDataTone() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _state.value = _state.value.copy(isPreviewingTone = true, error = null)
            try {
                val session = TransferSession(context = getApplication(), config = FskConfig.forCall())
                val previewSec = session.previewDurationSeconds(3)
                appendLog("Playing ~${previewSec}s sample buzz (checks speaker only)...")
                session.previewHandshakeTone(sampleSeconds = 3)
                val estSec = _state.value.compressionResult?.let {
                    session.estimateTransferSeconds(it.compressedSizeBytes)
                }
                appendLog("Preview done. Full image transfer est: ~${estSec ?: "?"}s (not the preview length).")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
                appendLog("Preview failed: ${e.message}")
            } finally {
                _state.value = _state.value.copy(isPreviewingTone = false)
            }
        }
    }

    fun send(hasCallPermission: Boolean) {
        val file = _state.value.compressionResult?.file
        val phone = _state.value.recipientPhone
        if (file == null) {
            _state.value = _state.value.copy(error = "Compress an image first")
            return
        }
        if (phone.isBlank()) {
            _state.value = _state.value.copy(error = "Enter recipient phone number")
            return
        }

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isSending = true,
                error = null,
                metrics = null,
                progress = null,
                logs = emptyList()
            )
            try {
                appendLog("Dialing $phone")
                OutgoingCallLauncher.dial(getApplication(), phone, hasCallPermission)
                val offHook = waitForOffHook(timeoutMs = 60_000)
                if (!offHook) throw IllegalStateException("Call not answered")
                appendLog("Call connected — preparing audio")

                val session = TransferSession(
                    context = getApplication(),
                    config = FskConfig.forCall(),
                    onProgress = { p -> _state.value = _state.value.copy(progress = p) },
                    onLog = { appendLog(it) }
                )
                val metrics = session.sendImage(file)
                _state.value = _state.value.copy(isSending = false, metrics = metrics)
                if (!metrics.success) {
                    _state.value = _state.value.copy(error = metrics.errorMessage)
                } else {
                    appendLog("Transfer complete")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSending = false, error = e.message)
                appendLog("Error: ${e.message}")
            }
        }
    }

    private fun appendLog(message: String) {
        _state.value = _state.value.copy(logs = _state.value.logs + message)
    }

    private suspend fun waitForOffHook(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (callMonitor.currentState() == CallState.OFFHOOK) return true
            kotlinx.coroutines.delay(500)
        }
        return false
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
