package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardProvisioningConfig
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDocumentsSidecars
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaServerSettings

/**
 * What the browser sees of a local stack: Grafana and the documents web server, each published on
 * `127.0.0.1` only.
 *
 * Grafana's datasources are built by the same code as a cluster's ([GrafanaDatasourceSet]), from the
 * spec's tenants and home tenant, so the UIDs and types are a cluster's and only the URLs differ
 * ([BackendUrls.LOCAL_COMPOSE]). Its server settings are a cluster's ([GrafanaServerSettings]). Its
 * one dashboard provider sweeps the provisioned tree in `dashboards/current`; that tree and the
 * staging trees beside it are written outside the composer. The documents web server is the
 * cluster's, forwarding to the read gate instead of a signer on loopback.
 */
object GrafanaViewPart {
    private const val GRAFANA_PORT = Constants.K8s.GRAFANA_PORT
    private const val PROVISIONING = "/etc/grafana/provisioning"
    private const val DATASOURCES_FILE = "grafana/provisioning/datasources/datasources.yaml"
    private const val DASHBOARDS_FILE = "grafana/provisioning/dashboards/dashboards.yaml"
    private const val DOCUMENTS_FILE = "documents-nginx.conf"
    private const val NGINX_CONFIG = "/etc/nginx/nginx.conf"

    /** Grafana runs as root, so it can write the `grafana-data` directory docker creates for it. */
    private const val ROOT_USER = "0"

    /** The provisioned dashboard tree, as Grafana sees it. */
    private const val DASHBOARD_TREE = "${Constants.LocalStack.DASHBOARDS_MOUNT}/${Constants.LocalStack.DASHBOARDS_TREE}"

    /** The part for [spec], whose documents come from [documentsBucket]. */
    fun compose(
        spec: LocalStackSpec,
        documentsBucket: String,
    ): ComposePart =
        ComposePart(
            services =
                mapOf(
                    Constants.LocalStack.Services.GRAFANA to grafana(spec),
                    Constants.LocalStack.Services.DOCUMENTS to documents(spec),
                ),
            files =
                mapOf(
                    LocalStackComposer.configPath(DATASOURCES_FILE) to
                        GrafanaDatasourceSet.build(spec.tenantSet, BackendUrls.LOCAL_COMPOSE).toYaml(),
                    LocalStackComposer.configPath(DASHBOARDS_FILE) to
                        GrafanaDashboardProvisioningConfig.forDashboardTree(DASHBOARD_TREE).toYaml(),
                    LocalStackComposer.configPath(DOCUMENTS_FILE) to
                        GrafanaDocumentsSidecars.renderWebServerConfig(
                            bucket = documentsBucket,
                            upstream = LocalStackComposer.STORE_GATE_ENDPOINT,
                            listenPort = Constants.Grafana.Documents.WEB_PORT,
                        ),
                ),
        )

    private fun grafana(spec: LocalStackSpec): ComposeService =
        ComposeService(
            image = LocalImages.GRAFANA,
            user = ROOT_USER,
            environment =
                GrafanaServerSettings(appTitle = "easy-db-lab local (${spec.mode.home})", dashboardRoot = DASHBOARD_TREE)
                    .environment()
                    .toMap(),
            volumes =
                listOf(
                    LocalStackComposer.configMount(DATASOURCES_FILE, "$PROVISIONING/datasources/datasources.yaml"),
                    LocalStackComposer.configMount(DASHBOARDS_FILE, "$PROVISIONING/dashboards/dashboards.yaml"),
                    ComposeMount.bind("./${Constants.LocalStack.DASHBOARDS_DIR}", Constants.LocalStack.DASHBOARDS_MOUNT, readOnly = true),
                    ComposeMount.bind("./${Constants.LocalStack.GRAFANA_DATA_DIR}", "/var/lib/grafana", readOnly = false),
                ),
            ports = listOf("${Constants.LocalStack.LOOPBACK}:${spec.grafanaPort}:$GRAFANA_PORT"),
            dependsOn =
                listOf(Constants.LocalStack.Services.MIMIR, Constants.LocalStack.Services.LOKI, Constants.LocalStack.Services.TEMPO)
                    .associateWith { ComposeDependency.STARTED },
            restart = "unless-stopped",
        )

    private fun documents(spec: LocalStackSpec): ComposeService =
        ComposeService(
            image = LocalImages.DOCUMENTS_WEB,
            volumes = listOf(LocalStackComposer.configMount(DOCUMENTS_FILE, NGINX_CONFIG)),
            ports = listOf("${Constants.LocalStack.LOOPBACK}:${spec.documentsPort}:${Constants.Grafana.Documents.WEB_PORT}"),
            dependsOn = mapOf(Constants.LocalStack.Services.STORE_GATE to ComposeDependency.STARTED),
            restart = "unless-stopped",
        )
}
