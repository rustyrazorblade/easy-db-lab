package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * One request a fake backend received: the tenant header it carried, and what it asked.
 *
 * @property tenant the `X-Scope-OrgID` value; `a|b` for several tenants.
 * @property headers the extra request headers.
 * @property timeout how long the caller waits for the answer.
 */
data class BackendCall(
    val tenant: String,
    val method: String,
    val port: Int,
    val path: String,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val timeout: Duration? = null,
) {
    /** The path without its query string. */
    val route: String get() = path.substringBefore('?')

    /** Every query parameter, decoded, in order; a repeated name appears once per value. */
    val params: List<Pair<String, String>>
        get() =
            path
                .substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() }
                .map { part ->
                    URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8) to
                        URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8)
                }

    /** The value of the query parameter [name], the first if repeated. */
    fun param(name: String): String? = params.firstOrNull { it.first == name }?.second
}

/**
 * The backends of a temporary stack, as the export's code sees them: one [ObservabilityHttp] per
 * tenant header, all answered by [answer] and recorded in [calls]. Tests route on the tenant, the
 * port and the path, the way the real backends would answer.
 */
class FakeBackends(
    private val answer: (BackendCall) -> ObservabilityResponse,
) {
    val calls = mutableListOf<BackendCall>()

    /** The client for requests that carry [tenant] in `X-Scope-OrgID`. */
    fun forTenant(tenant: String): ObservabilityHttp =
        object : ObservabilityHttp {
            override fun get(
                port: Int,
                pathAndQuery: String,
                timeout: Duration,
                headers: Map<String, String>,
            ): ObservabilityResponse = record(BackendCall(tenant, "GET", port, pathAndQuery, "", headers, timeout))

            override fun post(
                port: Int,
                path: String,
                body: String,
                contentType: String,
                timeout: Duration,
            ): ObservabilityResponse = record(BackendCall(tenant, "POST", port, path, body, timeout = timeout))
        }

    private fun record(call: BackendCall): ObservabilityResponse {
        synchronized(calls) { calls += call }
        return answer(call)
    }

    companion object {
        /** A Prometheus API success with [data] (raw JSON). */
        fun prometheus(data: String): ObservabilityResponse = ObservabilityResponse(200, """{"status":"success","data":$data}""")

        /** A series response: each map is one series' labels. */
        fun series(vararg series: Map<String, String>): ObservabilityResponse =
            prometheus(
                series.joinToString(",", "[", "]") { labels ->
                    labels.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
                },
            )
    }
}
