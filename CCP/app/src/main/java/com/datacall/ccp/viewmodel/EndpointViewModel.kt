package com.datacall.ccp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.datacall.ccp.data.EndpointEntity
import com.datacall.ccp.data.EndpointRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EndpointUiState(
    val endpoints: List<EndpointEntity> = emptyList(),
    val phoneInput: String = "",
    val labelInput: String = "",
    val error: String? = null
)

class EndpointViewModel(private val repository: EndpointRepository) : ViewModel() {
    private val _state = MutableStateFlow(EndpointUiState())
    val state: StateFlow<EndpointUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { list ->
                _state.value = _state.value.copy(endpoints = list)
            }
        }
    }

    fun setPhone(value: String) {
        _state.value = _state.value.copy(phoneInput = value, error = null)
    }

    fun setLabel(value: String) {
        _state.value = _state.value.copy(labelInput = value, error = null)
    }

    fun addEndpoint() {
        viewModelScope.launch {
            try {
                repository.add(_state.value.phoneInput, _state.value.labelInput)
                _state.value = _state.value.copy(phoneInput = "", labelInput = "", error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun deleteEndpoint(entity: EndpointEntity) {
        viewModelScope.launch { repository.delete(entity) }
    }
}
