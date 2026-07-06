package com.datacall.ccp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.datacall.ccp.call.CallSessionState
import com.datacall.ccp.call.DataTransferService
import com.datacall.ccp.data.EndpointEntity
import com.datacall.ccp.data.EndpointRepository
import com.datacall.ccp.session.TransferMetrics
import com.datacall.ccp.session.TransferProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ReceiverUiState(
    val trustedSenders: List<EndpointEntity> = emptyList(),
    val receivedImages: List<File> = emptyList(),
    val isListening: Boolean = false,
    val isReceiving: Boolean = false,
    val progress: TransferProgress? = null,
    val metrics: TransferMetrics? = null,
    val lastReceivedFile: File? = null,
    val incomingCallPending: Boolean = false,
    val error: String? = null
)

class ReceiverViewModel(
    app: Application,
    private val endpointRepository: EndpointRepository
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            endpointRepository.observeAll().collect { list ->
                _state.value = _state.value.copy(trustedSenders = list)
            }
        }
        refreshReceivedImages()
        syncFromCallState()
    }

    fun refreshReceivedImages() {
        val dir = File(getApplication<Application>().filesDir, "received")
        val files = dir.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        _state.value = _state.value.copy(receivedImages = files)
    }

    fun startListening() {
        CallSessionState.isListening = true
        CallSessionState.resetCall()
        DataTransferService.armListening(getApplication())
        syncFromCallState()
    }

    fun stopListening() {
        CallSessionState.stopListening()
        DataTransferService.stop(getApplication())
        _state.value = _state.value.copy(
            isListening = false,
            isReceiving = false,
            incomingCallPending = false,
            progress = null
        )
    }

    fun syncFromCallState() {
        _state.value = _state.value.copy(
            isListening = CallSessionState.isListening,
            isReceiving = CallSessionState.isReceiving,
            incomingCallPending = CallSessionState.incomingCallPending
        )
    }
}
