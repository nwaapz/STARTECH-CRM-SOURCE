package com.datacall.ccp.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.datacall.ccp.DataCallApp

class ViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val dataApp = app as DataCallApp
        return when {
            modelClass.isAssignableFrom(SenderViewModel::class.java) ->
                SenderViewModel(app, dataApp.endpointRepository) as T
            modelClass.isAssignableFrom(ReceiverViewModel::class.java) ->
                ReceiverViewModel(app, dataApp.endpointRepository) as T
            modelClass.isAssignableFrom(EndpointViewModel::class.java) ->
                EndpointViewModel(dataApp.endpointRepository) as T
            modelClass.isAssignableFrom(DiagnosticsViewModel::class.java) ->
                DiagnosticsViewModel(app) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
