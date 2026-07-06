package com.datacall.ccp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.datacall.ccp.modem.FskConfig
import com.datacall.ccp.protocol.DataPacket
import com.datacall.ccp.session.TransferSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val logs: List<String> = emptyList(),
    val loopbackResult: String? = null,
    val isRunning: Boolean = false,
    val isPreviewingTone: Boolean = false,
    val baudRate: Int = 1000
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    fun setBaudRate(rate: Int) {
        _state.value = _state.value.copy(baudRate = rate)
    }

    fun previewDataTone() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isPreviewingTone = true)
            val logs = _state.value.logs.toMutableList()
            try {
                logs.add("Playing ~3s sample buzz (speaker check only)...")
                val session = TransferSession(context = getApplication(), config = FskConfig.forCall())
                session.previewHandshakeTone(sampleSeconds = 3)
                logs.add("Preview done — full transfer would take much longer than this sample")
                _state.value = _state.value.copy(isPreviewingTone = false, logs = logs)
            } catch (e: Exception) {
                logs.add("Preview failed: ${e.message}")
                _state.value = _state.value.copy(isPreviewingTone = false, logs = logs)
            }
        }
    }

    fun runLoopbackTest() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRunning = true, loopbackResult = null)
            val logs = mutableListOf<String>()
            try {
                val config = FskConfig.forLoopback(_state.value.baudRate)
                val session = TransferSession(
                    context = getApplication(),
                    config = config,
                    loopback = true,
                    onLog = { msg -> logs.add(msg) }
                )
                val testBytes = "Hello loopback".toByteArray()
                val textResult = session.loopbackRoundTrip("DATA_CALL_V1")
                logs.add("Text loopback: $textResult")

                val testPacket = DataPacket(0, 1, testBytes)
                val packetOk = session.loopbackPacketRoundTrip(testPacket)
                logs.add("Packet loopback: $packetOk")

                val metrics = session.runLoopbackTest(testBytes)
                logs.add("Image loopback: success=${metrics.success}, bytes=${metrics.bytesTransferred}")

                _state.value = _state.value.copy(
                    isRunning = false,
                    logs = logs,
                    loopbackResult = if (textResult.contains("DATA_CALL") && packetOk) "PASS" else "FAIL: $textResult"
                )
            } catch (e: Exception) {
                logs.add("Error: ${e.message}")
                _state.value = _state.value.copy(isRunning = false, logs = logs, loopbackResult = "FAIL")
            }
        }
    }
}
