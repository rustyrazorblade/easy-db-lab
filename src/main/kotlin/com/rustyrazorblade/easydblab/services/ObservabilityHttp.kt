package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.time.Duration

/** A response from an observability service on the control node: its status code and body. */
data class ObservabilityResponse(
    val code: Int,
    val body: String,
)

/**
 * The one path from this tool to the observability services (Mimir, Loki, Tempo).
 *
 * Every request carries a tenant in `X-Scope-OrgID`; where it goes is an [ObservabilityEndpoint].
 * On a cluster ([DefaultObservabilityHttp]) it goes to the control node's private address and —
 * unless Tailscale reaches the private address directly — travels through the SOCKS tunnel, which
 * the per-client proxy selector of [HttpClientFactory] uses. The JVM-wide socks properties are
 * never touched. Readers (`logs query`, `spark logs`, MCP metrics) and the teardown flush all go
 * through it, so none of them can forget the tenant. A docker compose stack on this machine is
 * reached through [LocalhostObservabilityHttp].
 */
interface ObservabilityHttp {
    /**
     * GETs [pathAndQuery] from the service on [port], waiting up to [timeout] for the answer, with
     * [headers] besides the tenant (Loki's `X-Loki-Response-Encoding-Flags`, for one).
     */
    fun get(
        port: Int,
        pathAndQuery: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        headers: Map<String, String> = emptyMap(),
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
 * Where one observability request goes: the base URL of the service, the tenant it carries, and the
 * HTTP client that reaches it.
 *
 * @property baseUrl scheme, host and port of the service, with no trailing slash.
 * @property tenant the value of `X-Scope-OrgID`; `a|b` names several tenants.
 * @property client the client that reaches [baseUrl] (proxied for a cluster, direct for localhost).
 */
data class ObservabilityTarget(
    val baseUrl: String,
    val tenant: String,
    val client: OkHttpClient,
)

/**
 * The endpoint-and-tenant seam of [ObservabilityHttp]: it decides, per request, which host serves a
 * backend port, which tenant the request carries and which client reaches it. The cluster resolves
 * the control node through the tunnel ([ClusterObservabilityEndpoint]); a docker compose stack on
 * this machine resolves a published localhost port ([LocalhostObservabilityEndpoint]).
 */
fun interface ObservabilityEndpoint {
    /** The target that serves the backend listening on [port] (its cluster port, such as 3100). */
    fun target(port: Int): ObservabilityTarget
}

/**
 * [ObservabilityHttp] over OkHttp: every request goes to the [ObservabilityEndpoint]'s target for its
 * port and carries the target's tenant in `X-Scope-OrgID`.
 *
 * @property endpoint where each request goes.
 */
class EndpointObservabilityHttp(
    private val endpoint: ObservabilityEndpoint,
) : ObservabilityHttp {
    override fun get(
        port: Int,
        pathAndQuery: String,
        timeout: Duration,
        headers: Map<String, String>,
    ): ObservabilityResponse = send(port, pathAndQuery, timeout, headers) { it.get() }

    override fun post(
        port: Int,
        path: String,
        body: String,
        contentType: String,
        timeout: Duration,
    ): ObservabilityResponse = send(port, path, timeout, emptyMap()) { it.post(body.toRequestBody(contentType.toMediaType())) }

    private fun send(
        port: Int,
        pathAndQuery: String,
        timeout: Duration,
        headers: Map<String, String>,
        method: (Request.Builder) -> Request.Builder,
    ): ObservabilityResponse {
        val target = endpoint.target(port)
        val request =
            method(Request.Builder().url("${target.baseUrl}$pathAndQuery"))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .header(Constants.Observability.TENANT_HEADER, target.tenant)
                .build()
        val client =
            target.client
                .newBuilder()
                .readTimeout(timeout)
                .callTimeout(Duration.ZERO)
                .build()
        return client.newCall(request).execute().use { response ->
            ObservabilityResponse(response.code, response.body.string())
        }
    }
}

/**
 * The cluster's [ObservabilityEndpoint]: the control node's private address, the cluster's tenant,
 * and the cluster HTTP client. Unless Tailscale reaches the private address directly, it opens the
 * SOCKS tunnel first, which the client's per-client proxy selector uses.
 *
 * @property httpClientFactory the cluster HTTP client, with the SOCKS selector installed on it alone.
 * @property socksProxyService opens the tunnel when Tailscale is not active.
 * @property clusterStateManager the control node and the tenant.
 */
class ClusterObservabilityEndpoint(
    private val httpClientFactory: HttpClientFactory,
    private val socksProxyService: SocksProxyService,
    private val clusterStateManager: ClusterStateManager,
) : ObservabilityEndpoint {
    override fun target(port: Int): ObservabilityTarget {
        val clusterState = clusterStateManager.load()
        val controlHost: ClusterHost =
            clusterState.getControlHost() ?: error("No control node found. Please ensure the environment is running.")
        if (!clusterState.isTailscaleEnabled()) {
            socksProxyService.ensureRunning(controlHost)
        }
        return ObservabilityTarget("http://${controlHost.privateIp}:$port", clusterState.tenant(), httpClientFactory.createClient())
    }
}

/**
 * [ObservabilityHttp] to the cluster's control node: [EndpointObservabilityHttp] over a
 * [ClusterObservabilityEndpoint]. Every existing reader and the teardown flush use it.
 */
class DefaultObservabilityHttp(
    httpClientFactory: HttpClientFactory,
    socksProxyService: SocksProxyService,
    clusterStateManager: ClusterStateManager,
) : ObservabilityHttp by EndpointObservabilityHttp(
        ClusterObservabilityEndpoint(httpClientFactory, socksProxyService, clusterStateManager),
    )

/**
 * The [ObservabilityEndpoint] of a docker compose stack on this machine: each backend is reached on
 * the localhost port the stack publishes for it, with no proxy, and every request carries one tenant
 * (`a|b` for several). A backend the stack does not publish fails before any request is made.
 *
 * @property tenant the value of `X-Scope-OrgID` on every request.
 * @property publishedPorts the published localhost port of each backend, by its cluster port
 *   (for example `9009` to wherever the stack publishes Mimir).
 * @property client the HTTP client; direct by default, so no request can enter the SOCKS tunnel.
 */
class LocalhostObservabilityEndpoint(
    private val tenant: String,
    private val publishedPorts: Map<Int, Int>,
    private val client: OkHttpClient = LocalhostObservabilityHttp.directClient(),
) : ObservabilityEndpoint {
    override fun target(port: Int): ObservabilityTarget {
        val published = requireNotNull(publishedPorts[port]) { "The local stack publishes no backend on port $port" }
        return ObservabilityTarget("http://${Constants.LocalStack.LOOPBACK}:$published", tenant, client)
    }
}

/**
 * [ObservabilityHttp] to a docker compose stack on this machine: [EndpointObservabilityHttp] over a
 * [LocalhostObservabilityEndpoint]. The local stack and the export's temporary reader and writer
 * stacks use it. Each client keeps its own connection pool, so a caller that makes many of these
 * shares one [directClient] between them.
 */
class LocalhostObservabilityHttp(
    tenant: String,
    publishedPorts: Map<Int, Int>,
    client: OkHttpClient = directClient(),
) : ObservabilityHttp by EndpointObservabilityHttp(LocalhostObservabilityEndpoint(tenant, publishedPorts, client)) {
    companion object {
        /** An HTTP client that never uses a proxy: a local stack is on this machine. */
        fun directClient(): OkHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    }
}
