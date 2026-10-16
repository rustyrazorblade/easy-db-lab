package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.time.Duration

/**
 * A local stack's Grafana, on `127.0.0.1` at the port its manifest records: whether it answers, and
 * the provisioning reloads that make it read a rewritten datasource file or dashboard tree with no
 * restart.
 */
interface LocalGrafana {
    /** Whether a Grafana answers its health check on [port]. */
    fun isRunning(port: Int): Boolean

    /** Asks the Grafana on [port] to read its provisioned datasources again. */
    fun reloadDatasources(port: Int)

    /** Asks the Grafana on [port] to read its provisioned dashboards again. */
    fun reloadDashboards(port: Int)
}

/**
 * [LocalGrafana] over HTTP, with no proxy: the stack is on this machine, so no request may enter the
 * SOCKS tunnel. The reloads are server-admin API calls, sent with the admin's basic auth; anonymous
 * users are only org admins.
 *
 * @property client the HTTP client; direct by default.
 */
class LocalGrafanaHttp(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .proxy(Proxy.NO_PROXY)
            .callTimeout(TIMEOUT)
            .build(),
) : LocalGrafana {
    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        const val HEALTH = "/api/health"
        const val RELOAD = "/api/admin/provisioning/%s/reload"
    }

    override fun isRunning(port: Int): Boolean =
        try {
            client
                .newCall(
                    Request
                        .Builder()
                        .url(url(port, HEALTH))
                        .get()
                        .build(),
                ).execute()
                .use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }

    override fun reloadDatasources(port: Int) = reload(port, "datasources")

    override fun reloadDashboards(port: Int) = reload(port, "dashboards")

    private fun reload(
        port: Int,
        kind: String,
    ) {
        val request =
            Request
                .Builder()
                .url(url(port, RELOAD.format(kind)))
                .header("Authorization", Credentials.basic(Constants.Grafana.ADMIN_USER, Constants.Grafana.ADMIN_PASSWORD))
                .post(ByteArray(0).toRequestBody())
                .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "Grafana on port $port refused to reload its $kind: HTTP ${response.code} ${response.body.string()}"
            }
        }
    }

    private fun url(
        port: Int,
        path: String,
    ): String = "http://${Constants.LocalStack.LOOPBACK}:$port$path"
}
