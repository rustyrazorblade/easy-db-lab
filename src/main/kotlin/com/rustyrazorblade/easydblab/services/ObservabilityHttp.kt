package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceTarget
import com.rustyrazorblade.easydblab.services.access.baseUrl
import com.rustyrazorblade.easydblab.services.access.forEndpoint
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/** A response from an observability Service: its status code and body. */
data class ObservabilityResponse(
    val code: Int,
    val body: String,
)

/**
 * The one path from this tool to the observability Services (Mimir, Loki, Tempo).
 *
 * Every request carries the cluster's tenant in `X-Scope-OrgID` and goes to the endpoint the
 * service-access seam opens for its [ServiceTarget], with the proxy that endpoint names set on that
 * client alone. Readers (`logs query`, `spark logs`, `metrics stream`) and the teardown flush all go
 * through it, so none of them can forget the tenant or pick a route of its own.
 */
interface ObservabilityHttp {
    /** GETs [pathAndQuery] from [target], waiting up to [timeout] for the answer. */
    fun get(
        target: ServiceTarget,
        pathAndQuery: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): ObservabilityResponse

    /** POSTs [body] as [contentType] to [path] on [target], waiting up to [timeout]. */
    fun post(
        target: ServiceTarget,
        path: String,
        body: String = "",
        contentType: String = "application/json",
        timeout: Duration = DEFAULT_TIMEOUT,
    ): ObservabilityResponse

    companion object {
        /** The wait for an ordinary query. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/**
 * [ObservabilityHttp] over the service-access seam.
 *
 * Each request opens its Service through [access] again, so it follows a tunnel that was replaced
 * (or a forward that was reopened) since the last one; no client keeps a route of its own. A
 * `metrics stream` cycle passes an access built from the route it looked up, which never starts a
 * tunnel; every other command passes the process's seam.
 *
 * @property access opens the Service and names the route
 * @property tenant the cluster's tenant, sent in `X-Scope-OrgID`
 * @property httpClientFactory the base HTTP client the route is applied to
 */
class DefaultObservabilityHttp(
    val access: ServiceAccess,
    private val tenant: () -> String,
    private val httpClientFactory: HttpClientFactory,
) : ObservabilityHttp {
    private val log = KotlinLogging.logger {}

    override fun get(
        target: ServiceTarget,
        pathAndQuery: String,
        timeout: Duration,
    ): ObservabilityResponse = send(target, pathAndQuery, timeout) { it.get() }

    override fun post(
        target: ServiceTarget,
        path: String,
        body: String,
        contentType: String,
        timeout: Duration,
    ): ObservabilityResponse = send(target, path, timeout) { it.post(body.toRequestBody(contentType.toMediaType())) }

    private fun send(
        target: ServiceTarget,
        pathAndQuery: String,
        timeout: Duration,
        method: (Request.Builder) -> Request.Builder,
    ): ObservabilityResponse {
        val endpoint = access.open(target)
        // The path only: a query string can carry user input, and nothing here holds credentials.
        log.debug { "${target.service} request to ${endpoint.baseUrl()}${pathAndQuery.substringBefore('?')} via $endpoint" }
        val request =
            method(Request.Builder().url("${endpoint.baseUrl()}$pathAndQuery"))
                .header(Constants.Observability.TENANT_HEADER, tenant())
                .build()
        val client =
            httpClientFactory
                .createClient()
                .forEndpoint(endpoint)
                .newBuilder()
                .readTimeout(timeout)
                .callTimeout(Duration.ZERO)
                .build()
        return client.newCall(request).execute().use { response ->
            ObservabilityResponse(response.code, response.body.string())
        }
    }
}
