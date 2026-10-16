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
 * Grafana's server settings, as the `GF_*` environment variables Grafana reads at start.
 *
 * Every Grafana this tool runs takes them from here — the cluster's Deployment
 * ([GrafanaManifestBuilder]) and the local docker compose stack — so the two log in, brand, open
 * their home dashboard, install plugins (except offline) and show the documents iframe the same way. Only the values
 * that differ by host are parameters.
 *
 * @property appTitle the browser title, the cluster's `<name>-<id>` on a cluster.
 * @property dashboardRoot where the dashboard tree sits inside the Grafana container; the home
 *   dashboard is [Constants.Grafana.HOME_DASHBOARD_PATH] under it.
 * @property imageRenderer the image renderer, where one runs beside Grafana.
 * @property plugins the plugins Grafana downloads from grafana.com at start. An exported stack opens
 *   with no network, where a download failure stops Grafana (it exits 1), so it installs none; no
 *   dashboard it carries uses one of these.
 */
data class GrafanaServerSettings(
    val appTitle: String,
    val dashboardRoot: String = GRAFANA_DASHBOARD_ROOT,
    val imageRenderer: GrafanaImageRenderer? = null,
    val plugins: List<String> = DOWNLOADED_PLUGINS,
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
            // The Tests and comparison dashboards show a test's documents in an iframe in a Text panel.
            listOf(Constants.Grafana.DISABLE_SANITIZE_HTML_ENV to "true")

    private fun pluginEnvironment(): List<Pair<String, String>> =
        if (plugins.isEmpty()) emptyList() else listOf("GF_INSTALL_PLUGINS" to plugins.joinToString(","))

    private fun rendererEnvironment(): List<Pair<String, String>> =
        imageRenderer?.let {
            listOf(
                "GF_RENDERING_SERVER_URL" to it.serverUrl,
                "GF_RENDERING_CALLBACK_URL" to it.callbackUrl,
                "GF_RENDERING_RENDERER_TOKEN" to it.token,
            )
        } ?: emptyList()
}
