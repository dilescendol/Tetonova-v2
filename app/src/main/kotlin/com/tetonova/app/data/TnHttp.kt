package com.tetonova.app.data

import okhttp3.Dispatcher
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
    val client: OkHttpClient = OkHttpClient.Builder()
        // Home fans several parallel calls at the single panel host at once; OkHttp's default cap of
        // 5 requests/host would serialise them. Reuse still holds — they share one ConnectionPool.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .build()
}
