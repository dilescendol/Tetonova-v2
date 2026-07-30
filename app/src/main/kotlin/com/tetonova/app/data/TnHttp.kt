package com.tetonova.app.data

import com.tetonova.app.BuildConfig
import com.tetonova.app.Secrets
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * One app-wide OkHttp base. Every *Api/resolver derives its own timeouts via [client].newBuilder(),
 * which SHARES this Dispatcher + ConnectionPool — so repeated calls to the same host (the control
 * panel above all) reuse a pooled TLS connection instead of each class opening its own pool and
 * re-doing a handshake. Replaces ~15 independent OkHttpClient instances.
 *
 * ponytail: one shared pool with a per-host bump for the panel fan-out; only split a client out if
 * one genuinely needs isolation (different interceptor/cookie jar), which none currently do.
 */
object TnHttp {
    private val controlPanelHost by lazy {
        Secrets.controlPanelUrl.toHttpUrlOrNull()?.host.orEmpty()
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        // Home fans several parallel calls at the single panel host at once; OkHttp's default cap of
        // 5 requests/host would serialise them. Reuse still holds — they share one ConnectionPool.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .addInterceptor { chain ->
            val request = chain.request()
            val host = controlPanelHost
            if (host.isBlank() || !request.url.host.equals(host, ignoreCase = true)) {
                return@addInterceptor chain.proceed(request)
            }
            chain.proceed(
                request.newBuilder()
                    .header("X-TetoNova-Version-Code", BuildConfig.VERSION_CODE.toString())
                    .header("X-TetoNova-Version-Name", BuildConfig.VERSION_NAME)
                    .header("X-TetoNova-Package", BuildConfig.APPLICATION_ID)
                    .build(),
            )
        }
        .build()
}
