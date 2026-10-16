package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.time.Duration

/**
 * Grafana answered a provisioning reload with an error, so it still shows what it loaded before.
 *
 * @property kind `datasources` or `dashboards`.
 * @property status Grafana's HTTP status.
 * @property body Grafana's answer.
 */
class GrafanaReloadRefused(
    val kind: String,
    val status: Int,
    val body: String,
) : IllegalStateException("Grafana refused to reload its $kind: HTTP $status $body")

/**
 * Runs [reloads], which ask the Grafana at [grafanaUrl] to reload what was just rewritten in
 * [directory]. A refusal is reported as [Event.LocalStack.ReloadRefused], which says the files are
 * rewritten and a restart loads them, and fails the command.
 */
internal fun EventBus.reportingReloadRefusal(
    directory: File,
    grafanaUrl: String,
    reloads: () -> Unit,
) {
    try {
        reloads()
    } catch (e: GrafanaReloadRefused) {
        emit(Event.LocalStack.ReloadRefused(directory.path, grafanaUrl, e.kind, e.status, e.body))
        throw CommandFailedException("Grafana at $grafanaUrl refused to reload its ${e.kind}")
    }
}

/**
 * A local stack's Grafana, on `127.0.0.1` at the port its manifest records: whether it answers, and
 * the provisioning reloads that make it read a rewritten datasource file or dashboard tree with no
 * restart.
 */
interface LocalGrafana {
    /** Whether a Grafana answers its health check on [port]. */
    fun isRunning(port: Int): Boolean

    /**
     * Asks the Grafana on [port] to read its provisioned datasources again.
     *
     * @throws GrafanaReloadRefused when Grafana answers with an error.
     */
    fun reloadDatasources(port: Int)

    /**
     * Asks the Grafana on [port] to read its provisioned dashboards again.
     *
     * @throws GrafanaReloadRefused when Grafana answers with an error.
     */
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
            .readTimeout(TIMEOUT)
            .callTimeout(TIMEOUT)
            .build(),
) : LocalGrafana {
    private companion object {
        /**
         * A provisioning reload answers only once Grafana has read the whole tree, which takes tens of
         * seconds when it replaces every dashboard; OkHttp's 10s read timeout gives up first.
         */
        val TIMEOUT: Duration = Duration.ofMinutes(2)
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
            if (!response.isSuccessful) throw GrafanaReloadRefused(kind, response.code, response.body.string())
        }
    }

    private fun url(
        port: Int,
        path: String,
    ): String = "http://${Constants.LocalStack.LOOPBACK}:$port$path"
}
