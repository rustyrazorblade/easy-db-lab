package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardProvisioningConfig
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDocumentsSidecars
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaServerSettings
import com.rustyrazorblade.easydblab.services.TemplateService

/**
 * What the browser sees of a local stack: Grafana and the documents web server, each published on
 * `127.0.0.1` only.
 *
 * Grafana's datasources are built by the same code as a cluster's ([GrafanaDatasourceSet]), from the
 * spec's tenants and home tenant, so the UIDs and types are a cluster's and only the URLs differ
 * ([BackendUrls.LOCAL_COMPOSE]). Its server settings are a cluster's ([GrafanaServerSettings]), except
 * that it downloads no plugin: offline the download fails and Grafana exits, and live the download
 * stalled Grafana's start for minutes, while no dashboard a local stack installs uses one, and its data
 * source proxy may reach only the stack's own backends. Its one
 * dashboard provider sweeps the provisioned tree in `dashboards/current`; that tree and the
 * staging trees beside it are written outside the composer. The documents web server is the
 * cluster's, forwarding to the read gate (live) or serving the export's copy from disk (offline).
 */
object GrafanaViewPart {
    private const val GRAFANA_PORT = Constants.K8s.GRAFANA_PORT
    private const val PROVISIONING = "/etc/grafana/provisioning"
    private const val DATASOURCES_DIR = "grafana/provisioning/datasources"
    private const val DATASOURCES_FILE = "datasources.yaml"
    private const val DASHBOARDS_DIR = "grafana/provisioning/dashboards"
    private const val DASHBOARDS_FILE = "dashboards.yaml"
    private const val OFFLINE_DOCUMENTS_TEMPLATE = "documents-nginx-offline.conf"

    /** Where the offline documents view sees the export's data directory; its config's `root`. */
    private const val OFFLINE_DOCUMENTS_ROOT = "/data"

    /** Grafana runs as root, so it can write the `grafana-data` directory docker creates for it. */
    private const val ROOT_USER = "0"

    /** The provisioned dashboard tree, as Grafana sees it. */
    private const val DASHBOARD_TREE = "${Constants.LocalStack.DASHBOARDS_MOUNT}/${Constants.LocalStack.DASHBOARDS_TREE}"

    /** Where the documents view reads the reports, and what that means for the stack. */
    sealed interface Documents {
        /** The documents web server's configuration file. */
        fun config(): String

        /** What the documents web server mounts besides its configuration. */
        val mounts: List<ComposeMount>

        /** What the documents web server waits for. */
        val dependsOn: Map<String, ComposeDependency>?

        /**
         * The account bucket, read through the gate; the stack has the network.
         *
         * @property bucket the account bucket.
         */
        data class FromStore(
            val bucket: String,
        ) : Documents {
            override fun config(): String =
                GrafanaDocumentsSidecars.renderWebServerConfig(
                    bucket = bucket,
                    upstream = LocalStackComposer.STORE_GATE_ENDPOINT,
                    listenPort = Constants.Grafana.Documents.WEB_PORT,
                )

            override val mounts: List<ComposeMount> get() = emptyList()

            override val dependsOn: Map<String, ComposeDependency>
                get() = mapOf(Constants.LocalStack.Services.STORE_GATE to ComposeDependency.STARTED)
        }

        /**
         * The export's copy on disk, mounted read-only; the stack may have no network.
         *
         * @property dataDir the stack's data directory, relative to it.
         */
        data class FromDisk(
            val dataDir: String,
        ) : Documents {
            override fun config(): String =
                TemplateService
                    .Template(LocalStackComposer.resource(OFFLINE_DOCUMENTS_TEMPLATE), emptyMap())
                    .substitute(
                        mapOf(
                            "DOCUMENTS_WEB_PORT" to
                                Constants.Grafana.Documents.WEB_PORT
                                    .toString(),
                        ),
                    )

            override val mounts: List<ComposeMount>
                get() = listOf(ComposeMount.bind("./$dataDir", OFFLINE_DOCUMENTS_ROOT, readOnly = true))

            override val dependsOn: Map<String, ComposeDependency>? get() = null
        }
    }

    /** The part for [spec], whose documents come from [documents]. */
    fun compose(
        spec: LocalStackSpec,
        documents: Documents,
    ): ComposePart =
        ComposePart(
            services =
                mapOf(
                    Constants.LocalStack.Services.GRAFANA to grafana(spec),
                    Constants.LocalStack.Services.DOCUMENTS to documents(spec, documents),
                ),
            files =
                mapOf(
                    LocalStackComposer.configPath(DATASOURCES_DIR, DATASOURCES_FILE) to
                        GrafanaDatasourceSet.build(spec.tenantSet, BackendUrls.LOCAL_COMPOSE).toYaml(),
                    LocalStackComposer.configPath(DASHBOARDS_DIR, DASHBOARDS_FILE) to
                        GrafanaDashboardProvisioningConfig.forDashboardTree(DASHBOARD_TREE).toYaml(),
                    LocalStackComposer.configPath(Constants.LocalStack.Services.DOCUMENTS, LocalStackComposer.NGINX_CONFIG_FILE) to
                        documents.config(),
                ),
        )

    private fun grafana(spec: LocalStackSpec): ComposeService =
        ComposeService(
            image = LocalImages.GRAFANA,
            user = ROOT_USER,
            environment =
                GrafanaServerSettings(
                    appTitle = "easy-db-lab local (${spec.mode.home})",
                    dashboardRoot = DASHBOARD_TREE,
                    plugins = emptyList(),
                    dataProxyTimeoutSeconds = Constants.LocalStack.COLD_READ_TIMEOUT_SECONDS,
                    dataSourceProxyAllowList = BackendUrls.LOCAL_COMPOSE.hostPorts(),
                ).environment()
                    .toMap(),
            volumes =
                listOf(
                    LocalStackComposer.configMount(DATASOURCES_DIR, "$PROVISIONING/datasources"),
                    LocalStackComposer.configMount(DASHBOARDS_DIR, "$PROVISIONING/dashboards"),
                    ComposeMount.bind("./${Constants.LocalStack.DASHBOARDS_DIR}", Constants.LocalStack.DASHBOARDS_MOUNT, readOnly = true),
                    ComposeMount.bind("./${Constants.LocalStack.GRAFANA_DATA_DIR}", "/var/lib/grafana", readOnly = false),
                ),
            ports = listOf("${Constants.LocalStack.LOOPBACK}:${spec.grafanaPort}:$GRAFANA_PORT"),
            dependsOn =
                listOf(Constants.LocalStack.Services.MIMIR, Constants.LocalStack.Services.LOKI, Constants.LocalStack.Services.TEMPO)
                    .associateWith { ComposeDependency.STARTED },
            restart = "unless-stopped",
        )

    private fun documents(
        spec: LocalStackSpec,
        documents: Documents,
    ): ComposeService =
        ComposeService(
            image = LocalImages.DOCUMENTS_WEB,
            command = LocalStackComposer.nginxCommand,
            volumes =
                listOf(LocalStackComposer.configMount(Constants.LocalStack.Services.DOCUMENTS, LocalStackComposer.NGINX_CONFIG_DIR)) +
                    documents.mounts,
            ports = listOf("${Constants.LocalStack.LOOPBACK}:${spec.documentsPort}:${Constants.Grafana.Documents.WEB_PORT}"),
            dependsOn = documents.dependsOn,
            restart = "unless-stopped",
        )
}
