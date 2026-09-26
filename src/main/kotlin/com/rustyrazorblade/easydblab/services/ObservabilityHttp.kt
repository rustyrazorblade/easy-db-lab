package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/** A response from an observability service on the control node: its status code and body. */
data class ObservabilityResponse(
    val code: Int,
    val body: String,
)

/**
 * The one path from this tool to the observability services on the control node (Mimir, Loki).
 *
 * Every request goes to the control node's private address, carries the cluster's tenant in
 * `X-Scope-OrgID`, and — unless Tailscale reaches the private address directly — travels through
 * the SOCKS tunnel, which the per-client proxy selector of [HttpClientFactory] uses. The JVM-wide
 * socks properties are never touched. Readers (`logs query`, `spark logs`, MCP metrics) and the
 * teardown flush all go through it, so none of them can forget the tenant.
 */
interface ObservabilityHttp {
    /** GETs [pathAndQuery] from the service on [port], waiting up to [timeout] for the answer. */
    fun get(
        port: Int,
        pathAndQuery: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): ObservabilityResponse

    /** POSTs [body] as [contentType] to [path] on the service on [port], waiting up to [timeout]. */
    fun post(
        port: Int,
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
 * [ObservabilityHttp] over the cluster's proxied OkHttp client.
 *
 * @property httpClientFactory the cluster HTTP client, with the SOCKS selector installed on it alone.
 * @property socksProxyService opens the tunnel when Tailscale is not active.
 * @property clusterStateManager the control node and the tenant.
 */
class DefaultObservabilityHttp(
    private val httpClientFactory: HttpClientFactory,
    private val socksProxyService: SocksProxyService,
    private val clusterStateManager: ClusterStateManager,
) : ObservabilityHttp {
    override fun get(
        port: Int,
        pathAndQuery: String,
        timeout: Duration,
    ): ObservabilityResponse = send(port, pathAndQuery, timeout) { it.get() }

    override fun post(
        port: Int,
        path: String,
        body: String,
        contentType: String,
        timeout: Duration,
    ): ObservabilityResponse = send(port, path, timeout) { it.post(body.toRequestBody(contentType.toMediaType())) }

    private fun send(
        port: Int,
        pathAndQuery: String,
        timeout: Duration,
        method: (Request.Builder) -> Request.Builder,
    ): ObservabilityResponse {
        val clusterState = clusterStateManager.load()
        val controlHost: ClusterHost =
            clusterState.getControlHost() ?: error("No control node found. Please ensure the environment is running.")
        if (!clusterState.isTailscaleEnabled()) {
            socksProxyService.ensureRunning(controlHost)
        }
        val request =
            method(Request.Builder().url("http://${controlHost.privateIp}:$port$pathAndQuery"))
                .header(Constants.Observability.TENANT_HEADER, clusterState.tenant())
                .build()
        val client =
            httpClientFactory
                .createClient()
                .newBuilder()
                .readTimeout(timeout)
                .callTimeout(Duration.ZERO)
                .build()
        return client.newCall(request).execute().use { response ->
            ObservabilityResponse(response.code, response.body.string())
        }
    }
}
