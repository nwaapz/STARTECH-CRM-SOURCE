package com.example.smsgateway.lan

import android.content.Context
import com.example.smsgateway.ClubAutoMessageScheduler
import com.example.smsgateway.CommunicationLog
import com.example.smsgateway.MassSmsQueueService
import com.example.smsgateway.WhatsAppCampaignService
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.data.GatewayDatabase
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket

class LanHttpServer(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val database: GatewayDatabase,
) {
    private val appContext = context.applicationContext
    @Volatile
    private var engine: ApplicationEngine? = null
    @Volatile
    private var serverThread: Thread? = null

    val isRunning: Boolean
        get() = engine != null

    fun start() {
        if (engine != null) {
            LanTraceLog.w(TAG, "start() skipped — server already running")
            return
        }
        if (!settingsStore.isLanServerEnabled()) {
            LanTraceLog.w(TAG, "start() skipped — LAN CRM disabled in settings")
            return
        }
        if (serverThread?.isAlive == true) {
            LanTraceLog.w(TAG, "start() skipped — server thread already starting/running")
            return
        }

        val port = settingsStore.getLanPort()
        LanTraceLog.i(TAG, "start() requested on port $port")

        serverThread = Thread {
            var startedEngine: ApplicationEngine? = null
            try {
                runBlocking {
                    CommunicationLog.getInstance(appContext).migrateFromActivityLogIfNeeded()
                    CommunicationLog.getInstance(appContext).backfillIfEmpty()
                    val commCount = database.communicationMessageDao().countAll()
                    LanTraceLog.i(TAG, "communication_messages count after migration/backfill: $commCount")
                    MassSmsQueueService.getInstance(appContext).recoverOnStartup()
                    WhatsAppCampaignService.getInstance(appContext).recoverOnStartup()
                    ClubAutoMessageScheduler.getInstance(appContext).start()
                }

                if (!isPortAvailable(port)) {
                    LanTraceLog.e(TAG, "Port $port is already in use by another app")
                    return@Thread
                }

                LanTraceLog.i(TAG, "Creating CIO server on 0.0.0.0:$port ...")
                startedEngine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
                    install(CORS) {
                        anyHost()
                        allowHeader(HttpHeaders.Authorization)
                        allowHeader(HttpHeaders.ContentType)
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Put)
                        allowMethod(HttpMethod.Delete)
                        allowMethod(HttpMethod.Options)
                    }
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                encodeDefaults = true
                            },
                        )
                    }
                    configureLanRequestTracing()
                    configureLanRoutes(appContext, settingsStore, database)
                }
                engine = startedEngine
                LanTraceLog.i(TAG, "Engine created — starting accept loop (blocking thread)")
                LanTraceLog.i(TAG, "CRM URL: ${LanNetworkUtils.buildLanUrl(appContext, port)}")

                Thread {
                    Thread.sleep(500)
                    LanStartupDiagnostics.run(appContext, settingsStore, port)
                }.start()

                startedEngine.start(wait = true)
            } catch (e: Exception) {
                LanTraceLog.e(TAG, "Server START FAILED", e)
            } finally {
                engine = null
                startedEngine = null
                serverThread = null
                LanTraceLog.w(TAG, "Server accept loop ended")
            }
        }.apply {
            name = "LanHttpServer"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        LanTraceLog.i(TAG, "stop() requested (running=${engine != null})")
        val currentEngine = engine
        if (currentEngine != null) {
            try {
                currentEngine.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
                LanTraceLog.i(TAG, "Server STOPPED")
            } catch (e: Exception) {
                LanTraceLog.e(TAG, "Error while stopping server", e)
            }
        }
        engine = null
        serverThread?.interrupt()
        serverThread = null
    }

    fun restartIfNeeded() {
        LanTraceLog.i(TAG, "restartIfNeeded() lanEnabled=${settingsStore.isLanServerEnabled()}")
        if (!settingsStore.isLanServerEnabled()) {
            stop()
            return
        }
        stop()
        Thread.sleep(500)
        start()
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "Server"

        @Volatile
        private var instance: LanHttpServer? = null

        fun getInstance(context: Context): LanHttpServer {
            return instance ?: synchronized(this) {
                instance ?: LanHttpServer(
                    context.applicationContext,
                    SettingsStore.getInstance(context),
                    GatewayDatabase.getInstance(context),
                ).also { instance = it }
            }
        }
    }
}
