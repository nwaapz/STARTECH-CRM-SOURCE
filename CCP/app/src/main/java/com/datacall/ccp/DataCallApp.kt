package com.datacall.ccp

import android.app.Application
import com.datacall.ccp.data.AppDatabase
import com.datacall.ccp.data.AppPreferences
import com.datacall.ccp.data.EndpointRepository

class DataCallApp : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
    val endpointRepository by lazy { EndpointRepository(database.endpointDao()) }
    val preferences by lazy { AppPreferences(this) }
}
