package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * Grafana answered every dashboard reload, but after the last one it still did not serve these
 * dashboards of the tree on disk.
 *
 * @property missing the uids Grafana does not serve.
 * @property reloads how many reloads were asked.
 */
class GrafanaDashboardsNotServed(
    val missing: List<String>,
    val reloads: Int,
) : IllegalStateException("Grafana does not serve ${missing.size} dashboards after $reloads reloads: ${missing.joinToString(", ")}")

/**
 * Runs [reloads], which ask the Grafana at [grafanaUrl] to reload what was just rewritten in
 * [directory]. A refusal is reported as [Event.LocalStack.ReloadRefused], and dashboards Grafana
 * does not serve after every reload as [Event.LocalStack.DashboardsNotServed]; each says the files
 * are rewritten and a restart loads them, and fails the command.
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
    } catch (e: GrafanaDashboardsNotServed) {
        emit(Event.LocalStack.DashboardsNotServed(directory.path, grafanaUrl, e.missing, e.reloads))
        throw CommandFailedException("Grafana at $grafanaUrl does not serve the installed dashboards")
    }
}

/**
 * A local stack's Grafana, on `127.0.0.1` at the port its manifest records: whether it answers, the
 * provisioning reloads that make it read a rewritten datasource file or dashboard tree with no
 * restart, and whether it serves a dashboard.
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

    /**
     * Whether the Grafana on [port] serves the dashboard [uid], so a reload can be checked: Grafana
     * answers a reload 200 whatever it read.
     *
     * @throws IllegalStateException when Grafana answers with anything but the dashboard or not found.
     */
    fun servesDashboard(
        port: Int,
        uid: String,
    ): Boolean
}

/**
 * [LocalGrafana] over HTTP, with no proxy: the stack is on this machine, so no request may enter the
 * SOCKS tunnel. The reloads are server-admin API calls, sent with the admin's basic auth; anonymous
 * users are only org admins. The dashboard lookup is sent the same way.
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
        const val DASHBOARD = "/api/dashboards/uid"
        const val HTTP_NOT_FOUND = 404
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

    override fun servesDashboard(
        port: Int,
        uid: String,
    ): Boolean {
        // A dashboard from --from may carry any uid, so it is one encoded path segment.
        val request = asAdmin(url(port, DASHBOARD).newBuilder().addPathSegment(uid).build()).get().build()
        return client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> true
                response.code == HTTP_NOT_FOUND -> false
                else -> error("Grafana answered the lookup of dashboard $uid with HTTP ${response.code} ${response.body.string()}")
            }
        }
    }

    private fun reload(
        port: Int,
        kind: String,
    ) {
        val request = asAdmin(url(port, RELOAD.format(kind))).post(ByteArray(0).toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GrafanaReloadRefused(kind, response.code, response.body.string())
        }
    }

    private fun asAdmin(url: HttpUrl): Request.Builder =
        Request
            .Builder()
            .url(url)
            .header("Authorization", Credentials.basic(Constants.Grafana.ADMIN_USER, Constants.Grafana.ADMIN_PASSWORD))

    private fun url(
        port: Int,
        path: String,
    ): HttpUrl = "http://${Constants.LocalStack.LOOPBACK}:$port$path".toHttpUrl()
}
