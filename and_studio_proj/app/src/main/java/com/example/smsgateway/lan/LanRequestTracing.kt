package com.example.smsgateway.lan

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.util.pipeline.intercept

fun Application.configureLanRequestTracing() {
    intercept(ApplicationCallPipeline.Monitoring) {
        val start = System.currentTimeMillis()
        val method = call.request.httpMethod.value
        val uri = call.request.uri
        LanTraceLog.i("HTTP", ">>> $method $uri")
        var status = 0
        try {
            proceed()
            status = call.response.status()?.value ?: 0
        } catch (t: Throwable) {
            status = call.response.status()?.value ?: 500
            LanTraceLog.e("HTTP", "!!! $method $uri failed", t)
            throw t
        } finally {
            val ms = System.currentTimeMillis() - start
            LanTraceLog.i("HTTP", "<<< $method $uri -> HTTP $status (${ms}ms)")
        }
    }
}
