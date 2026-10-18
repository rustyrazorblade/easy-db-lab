package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants

/**
 * Where Grafana sends a panel to be rendered as an image, and the token both sides share.
 *
 * @property serverUrl the renderer's `/render` URL, as Grafana reaches it.
 * @property callbackUrl Grafana's own URL, as the renderer reaches it.
 * @property token the shared secret the renderer checks.
 */
data class GrafanaImageRenderer(
    val serverUrl: String,
    val callbackUrl: String,
    val token: String,
)

/**
 * The one host Grafana answers to (`[server] domain` with `enforce_domain`): a request whose Host,
 * without its port, is any other is redirected to [rootUrl] with the same path. Grafana's own
 * health check, `/api/health`, answers every host.
 *
 * @property domain the host the browser and every client use.
 * @property rootUrl Grafana's URL at that host, where a foreign host is redirected; it ends with `/`.
 */
data class GrafanaServerDomain(
    val domain: String,
    val rootUrl: String,
)

/**
 * Grafana's server settings, as the `GF_*` environment variables Grafana reads at start.
 *
 * Every Grafana this tool runs takes them from here — the cluster's Deployment
 * ([GrafanaManifestBuilder]) and the local docker compose stack — so the two log in, brand, open
 * their home dashboard, install plugins (a cluster's only) and show the documents iframe the same way. Only the values
 * that differ by host are parameters.
 *
 * @property appTitle the browser title, the cluster's `<name>-<id>` on a cluster.
 * @property dashboardRoot where the dashboard tree sits inside the Grafana container; the home
 *   dashboard is [Constants.Grafana.HOME_DASHBOARD_PATH] under it.
 * @property imageRenderer the image renderer, where one runs beside Grafana.
 * @property plugins the plugins Grafana downloads from grafana.com at start. A local stack installs
 *   none: an exported stack opens with no network, where a download failure stops Grafana (it
 *   exits 1), and a live one stalled minutes on the download. No dashboard a local stack installs
 *   uses one of these.
 * @property dataProxyTimeoutSeconds how long Grafana waits for a datasource, or Grafana's 30-second
 *   default when null. A local stack sets it: a cold query there reads every block from S3.
 * @property dataSourceProxyAllowList the only `host:port`s Grafana's data source proxy route
 *   (`/api/datasources/proxy/...`) may reach, or any when empty. Grafana 13.2.2 checks the list only
 *   on that route: a backend plugin's queries (`/api/ds/query`) and resource calls
 *   (`/api/datasources/uid/<uid>/resources/...`), which is how Prometheus, Loki and Tempo
 *   datasources query, reach whatever URL the datasource names. A local stack names its own
 *   backends, which narrows the proxy route only; what keeps a datasource an anonymous Admin adds
 *   from reaching the signer is the stack's networks, on which Grafana cannot reach it.
 * @property serverDomain the one host Grafana answers to, or any when null. A local stack sets its
 *   published loopback URL, so a page in the operator's browser that rebinds its own name to
 *   127.0.0.1 is redirected away instead of driving the anonymous Admin. A cluster's Grafana is
 *   reached by IP over the tunnel and sets none.
 */
data class GrafanaServerSettings(
    val appTitle: String,
    val dashboardRoot: String = GRAFANA_DASHBOARD_ROOT,
    val imageRenderer: GrafanaImageRenderer? = null,
    val plugins: List<String> = DOWNLOADED_PLUGINS,
    val dataProxyTimeoutSeconds: Int? = null,
    val dataSourceProxyAllowList: List<String> = emptyList(),
    val serverDomain: GrafanaServerDomain? = null,
) {
    companion object {
        /**
         * The plugins a cluster's Grafana downloads at start. Only externally-distributed plugins
         * belong here. The Pyroscope datasource (grafana-pyroscope-datasource) is BUNDLED as a core
         * plugin in Grafana 13.x — listing it makes the boot-time install step fail ("cannot install
         * a Core plugin") and Grafana CrashLoopBackOffs. It is still declared as a datasource in
         * GrafanaDatasourceConfig; the bundled plugin serves it without an install.
         */
        val DOWNLOADED_PLUGINS = listOf("grafana-clickhouse-datasource", "grafana-polystat-panel")
    }

    /** The environment, in the order Grafana's container lists it. */
    fun environment(): List<Pair<String, String>> =
        pluginEnvironment() +
            listOf(
                "GF_SECURITY_ADMIN_USER" to Constants.Grafana.ADMIN_USER,
                "GF_SECURITY_ADMIN_PASSWORD" to Constants.Grafana.ADMIN_PASSWORD,
                "GF_USERS_ALLOW_SIGN_UP" to "false",
                "GF_AUTH_ANONYMOUS_ENABLED" to "true",
                "GF_USERS_DEFAULT_ORG_NAME" to "Main Org.",
                "GF_AUTH_ANONYMOUS_ORG_NAME" to "Main Org.",
                "GF_AUTH_ANONYMOUS_ORG_ROLE" to "Admin",
                "GF_AUTH_DISABLE_LOGIN_FORM" to "false",
                "GF_AUTH_BASIC_ENABLED" to "true",
                "GF_BRANDING_APP_TITLE" to appTitle,
                "GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH" to "$dashboardRoot/${Constants.Grafana.HOME_DASHBOARD_PATH}",
            ) + rendererEnvironment() +
            listOfNotNull(dataProxyTimeoutSeconds?.let { "GF_DATAPROXY_TIMEOUT" to it.toString() }) +
            listOfNotNull(
                dataSourceProxyAllowList.takeIf { it.isNotEmpty() }?.let {
                    "GF_SECURITY_DATA_SOURCE_PROXY_WHITELIST" to
                        it.joinToString(
                            " ",
                        )
                },
            ) + domainEnvironment() +
            // The Tests and comparison dashboards show a test's documents in an iframe in a Text panel.
            listOf(Constants.Grafana.DISABLE_SANITIZE_HTML_ENV to "true")

    private fun pluginEnvironment(): List<Pair<String, String>> =
        if (plugins.isEmpty()) emptyList() else listOf("GF_INSTALL_PLUGINS" to plugins.joinToString(","))

    private fun domainEnvironment(): List<Pair<String, String>> =
        serverDomain?.let {
            listOf(
                "GF_SERVER_DOMAIN" to it.domain,
                "GF_SERVER_ROOT_URL" to it.rootUrl,
                "GF_SERVER_ENFORCE_DOMAIN" to "true",
            )
        } ?: emptyList()

    private fun rendererEnvironment(): List<Pair<String, String>> =
        imageRenderer?.let {
            listOf(
                "GF_RENDERING_SERVER_URL" to it.serverUrl,
                "GF_RENDERING_CALLBACK_URL" to it.callbackUrl,
                "GF_RENDERING_RENDERER_TOKEN" to it.token,
            )
        } ?: emptyList()
}
