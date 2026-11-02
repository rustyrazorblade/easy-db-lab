package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration

/**
 * [ObservabilityHttp] for one `metrics stream` cycle: it sends every request over the [route] the
 * cycle looked up, to the control node and in the tenant of the cycle's [state].
 *
 * It never starts, stops or replaces a tunnel.  A [ObservabilityRoute.Socks] route becomes an
 * explicit per-client `java.net.Proxy(SOCKS, 127.0.0.1:<port>)`, which overrides the base client's
 * proxy selector: that selector reads this JVM's published tunnel port, which can name a tunnel
 * another process has since replaced.  A [ObservabilityRoute.Direct] route uses no proxy.  The
 * JVM-wide socks properties are never touched.
 *
 * @throws IllegalStateException from [get] and [post] when [state] has no control node.
 */
class RoutedObservabilityHttp(
    private val state: ClusterState,
    private val route: ObservabilityRoute,
    private val httpClientFactory: HttpClientFactory,
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
        val controlHost = state.getControlHost() ?: error("No control node found. Please ensure the environment is running.")
        return httpClientFactory
            .createClient()
            .newBuilder()
            .proxy(proxyFor(route))
            .build()
            .controlNodeCall(ControlNodeTarget(controlHost.privateIp, state.tenant()), port, pathAndQuery, timeout, method)
    }

    private fun proxyFor(route: ObservabilityRoute): Proxy =
        when (route) {
            ObservabilityRoute.Direct -> Proxy.NO_PROXY
            is ObservabilityRoute.Socks ->
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(Constants.Proxy.TUNNEL_LISTEN_ADDRESS, route.port))
        }
}
