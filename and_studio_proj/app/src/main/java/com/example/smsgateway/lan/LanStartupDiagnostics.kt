package com.example.smsgateway.lan

import android.content.Context
import com.example.smsgateway.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

object LanStartupDiagnostics {
    private const val TAG = "Startup"

    fun run(context: Context, settingsStore: SettingsStore, port: Int) {
        LanTraceLog.i(TAG, "=== LAN CRM diagnostics start ===")
        logSettings(settingsStore)
        val lanHost = logNetworkInterfaces(context, settingsStore, port)
        logAssetInventory(context)
        runSelfTests(port, lanHost)
        LanTraceLog.i(TAG, "=== LAN CRM diagnostics end ===")
    }

    private fun logSettings(settingsStore: SettingsStore) {
        LanTraceLog.i(
            TAG,
            "lanEnabled=${settingsStore.isLanServerEnabled()} " +
                "port=${settingsStore.getLanPort()} " +
                "verification=${settingsStore.getVerificationMode()} " +
                "serviceEnabled=${settingsStore.isServiceEnabled()}",
        )
        LanTraceLog.i(TAG, "PIN length=${settingsStore.getLanPin().length} (value hidden)")
    }

    private fun logNetworkInterfaces(context: Context, settingsStore: SettingsStore, port: Int): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            if (interfaces.isEmpty()) {
                LanTraceLog.w(TAG, "No network interfaces found")
            } else {
                for (intf in interfaces) {
                    val name = intf.name
                    val up = intf.isUp
                    val loopback = intf.isLoopback
                    val addresses = Collections.list(intf.inetAddresses)
                        .mapNotNull { addr ->
                            if (addr is Inet4Address) addr.hostAddress else null
                        }
                        .joinToString(", ")
                    LanTraceLog.i(TAG, "iface $name up=$up loopback=$loopback ipv4=[$addresses]")
                }
            }
            val detectedHost = LanNetworkUtils.getLocalIpAddress(context)
            val overrideHost = settingsStore.getLanHostOverride()
            val url = LanNetworkUtils.buildLanUrl(context, port)
            LanTraceLog.i(TAG, "Detected host: $detectedHost")
            if (overrideHost.isNotBlank()) {
                LanTraceLog.i(TAG, "Custom CRM host override: $overrideHost")
            }
            LanTraceLog.i(TAG, "Suggested CRM URL: $url")
            val lanHost = overrideHost.ifBlank { detectedHost }
            return lanHost
        } catch (e: Exception) {
            LanTraceLog.e(TAG, "Failed to enumerate interfaces", e)
            return "192.168.43.1"
        }
    }

    private fun logAssetInventory(context: Context) {
        try {
            val assets = context.assets
            val crmTop = assets.list("crm")?.toList().orEmpty()
            LanTraceLog.i(TAG, "assets/crm/: ${crmTop.joinToString(", ").ifEmpty { "(empty)" }}")
            val crmAssets = assets.list("crm/assets")?.toList().orEmpty()
            LanTraceLog.i(TAG, "assets/crm/assets/: ${crmAssets.joinToString(", ").ifEmpty { "(empty)" }}")
            if (!crmTop.contains("index.html")) {
                LanTraceLog.e(TAG, "Missing crm/index.html — run: cd crm-web && npm run build")
            }
            if (crmAssets.none { it.endsWith(".js") }) {
                LanTraceLog.e(TAG, "Missing JS bundle in crm/assets/")
            }
        } catch (e: Exception) {
            LanTraceLog.e(TAG, "Asset inventory failed", e)
        }
    }

    private fun runSelfTests(port: Int, lanHost: String) {
        Thread {
            try {
                Thread.sleep(800)
                testTcpConnect("127.0.0.1", port)
                testTcpConnect(lanHost, port)

                val client = OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
                val urls = listOf(
                    "http://127.0.0.1:$port/api/ping",
                    "http://$lanHost:$port/api/ping",
                    "http://$lanHost:$port/api/health",
                )
                for (url in urls) {
                    try {
                        LanTraceLog.i(TAG, "Self-test GET $url")
                        val response = client.newCall(Request.Builder().url(url).build()).execute()
                        val body = response.body?.string()?.take(200).orEmpty()
                        LanTraceLog.i(TAG, "Self-test OK $url -> HTTP ${response.code} body=[$body]")
                        response.close()
                    } catch (e: Exception) {
                        LanTraceLog.e(TAG, "Self-test FAIL $url", e)
                    }
                }

                LanTraceLog.w(
                    TAG,
                    "If phone self-test OK but PC curl fails: hotspot may block PC->phone on port $port. " +
                        "Try: PC shares Wi-Fi hotspot, phone joins PC network, use phone's new IP.",
                )
            } catch (e: Exception) {
                LanTraceLog.e(TAG, "Self-test thread error", e)
            }
        }.start()
    }

    private fun testTcpConnect(host: String, port: Int) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3000)
                LanTraceLog.i(TAG, "TCP connect OK $host:$port")
            }
        } catch (e: Exception) {
            LanTraceLog.e(TAG, "TCP connect FAIL $host:$port", e)
        }
    }
}
