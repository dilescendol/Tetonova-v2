package com.tetonova.core.scraper

import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime-only source secrets/config pushed in by the Android app or server scraper from the panel.
 * Keep this out of static parser logic so access codes can rotate without rebuilding.
 */
internal object LiveRuntimeConfig {
    private val accessCodesByHost = ConcurrentHashMap<String, String>()
    @Volatile private var premiumProxy = PremiumProxy()

    private data class PremiumProxy(val base: String = "", val bearer: String = "")
    data class ProxiedRequest(val url: String, val bearer: String)

    fun setPremiumProxy(panelBase: String, bearer: String) {
        premiumProxy = PremiumProxy(panelBase.trim().trimEnd('/'), bearer.trim())
    }

    fun proxiedRequestFor(url: String): ProxiedRequest? {
        val host = hostOf(url) ?: return null
        val (base, bearer) = premiumProxy
        if (!host.endsWith(".goodbos.online") || base.isBlank() || bearer.isBlank()) return null
        return ProxiedRequest(
            "$base/api/v1/me/premium/fetch?url=${URLEncoder.encode(url, "UTF-8")}",
            bearer,
        )
    }

    fun setAccessCodes(codesByBaseUrl: Map<String, String>) {
        accessCodesByHost.clear()
        codesByBaseUrl.forEach { (baseUrl, code) ->
            val host = hostOf(baseUrl) ?: return@forEach
            val clean = code.trim()
            if (clean.isNotBlank()) accessCodesByHost[host] = clean
        }
    }

    fun accessCodeFor(url: String): String? {
        val host = hostOf(url) ?: return null
        accessCodesByHost[host]?.let { return it }
        return accessCodesByHost.entries.firstOrNull { (known, _) ->
            host == known || host.endsWith(".$known") || known.endsWith(".$host")
        }?.value
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }
}
