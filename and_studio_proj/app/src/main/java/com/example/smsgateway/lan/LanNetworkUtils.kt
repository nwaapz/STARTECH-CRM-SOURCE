package com.example.smsgateway.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import com.example.smsgateway.SettingsStore
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object LanNetworkUtils {
    private const val TAG = "LanNetworkUtils"
    private const val HOTSPOT_FALLBACK_IP = "192.168.43.1"

    fun getLocalIpAddress(context: Context): String {
        findHotspotInterfaceIp()?.let { return it }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return HOTSPOT_FALLBACK_IP
        val active = connectivityManager.activeNetwork ?: return HOTSPOT_FALLBACK_IP
        val props = connectivityManager.getLinkProperties(active) ?: return HOTSPOT_FALLBACK_IP
        findIpv4(props)?.let { return it }

        return HOTSPOT_FALLBACK_IP
    }

    fun buildLanUrl(context: Context, port: Int): String {
        val settings = SettingsStore.getInstance(context)
        val override = settings.getLanHostOverride()
        val host = override.ifBlank { getLocalIpAddress(context) }
        return "http://$host:$port"
    }

    /**
     * Parses user input: bare IP/hostname, or full URL like http://28.144.123.105:8765/
     * Returns normalized host and optional port override.
     */
    fun parseLanHostInput(raw: String): ParsedLanHost? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val withoutScheme = trimmed
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()
        if (withoutScheme.isEmpty()) return null

        val hostPart = withoutScheme.substringBefore(':').trim()
        if (hostPart.isEmpty() || hostPart.contains(' ')) return null

        val portPart = withoutScheme.substringAfter(':', missingDelimiterValue = "")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.toIntOrNull()

        return ParsedLanHost(host = hostPart, port = portPart)
    }

    data class ParsedLanHost(val host: String, val port: Int?)

    fun buildLanUrlFromInterfaces(port: Int = SettingsStore.DEFAULT_LAN_PORT): String {
        val ip = findHotspotInterfaceIp() ?: HOTSPOT_FALLBACK_IP
        return "http://$ip:$port"
    }

    private fun findHotspotInterfaceIp(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var fallback: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val isLikelyAp = name.contains("ap") || name.contains("wlan") || name.contains("swlan")
                for (address in Collections.list(intf.inetAddresses)) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host.startsWith("192.168.43.")) return host
                    if (isLikelyAp && host.startsWith("192.168.")) return host
                    if (fallback == null && host.startsWith("192.168.")) {
                        fallback = host
                    }
                }
            }
            fallback
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces", e)
            null
        }
    }

    private fun findIpv4(props: LinkProperties): String? {
        for (address in props.linkAddresses) {
            val host = address.address
            if (host is Inet4Address && !host.isLoopbackAddress) {
                return host.hostAddress
            }
        }
        return null
    }
}
