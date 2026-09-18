package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for GrafanaManifestBuilder.
 *
 * Uses real TemplateService (never mocked per project convention).
 */
class GrafanaManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: GrafanaManifestBuilder
    private lateinit var templateService: TemplateService
    private lateinit var mockClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = getKoin().get()
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            ),
        )
        templateService = getKoin().get()
        builder = GrafanaManifestBuilder(templateService)
    }

    private fun grafanaContainer() =
        builder
            .buildDeployment()
            .spec.template.spec.containers
            .first { it.name == "grafana" }

    @Test
    fun `the provisioning ConfigMap carries the dashboard tree provider config`() {
        // The shape of that config is pinned in GrafanaDashboardProvisioningConfigTest; here only
        // that the ConfigMap ships it unchanged under the key Grafana mounts.
        val yaml = builder.buildDashboardProvisioningConfigMap().data["dashboards.yaml"]

        assertThat(yaml).isEqualTo(GrafanaDashboardProvisioningConfig.forDashboardTree().toYaml())
    }

    @Test
    fun `the home dashboard resolves inside the copied tree`() {
        // GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH is the home dashboard's place in the tree,
        // the same path the catalog insists on. Left pointing anywhere else, Grafana opens on an
        // empty page.
        val homePath = grafanaContainer().env.first { it.name == "GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH" }.value

        assertThat(homePath).isEqualTo("/var/lib/grafana/dashboards/infrastructure/system-overview.json")
    }

    @Test
    fun `buildAllResources is only the provisioning ConfigMap and the Deployment`() {
        // Dashboards reach Grafana as files on the hostPath, not as K8s objects, so nothing here
        // may vary with the catalog's contents.
        val names = builder.buildAllResources().map { it.metadata.name }

        assertThat(names).containsExactly("grafana-dashboards-config", "grafana")
    }

    @Test
    fun `buildDeployment includes grafana and image renderer containers`() {
        val deployment = builder.buildDeployment()
        val containers = deployment.spec.template.spec.containers

        assertThat(containers).hasSize(2)
        assertThat(containers.map { it.name }).containsExactly("grafana", "grafana-image-renderer")
    }

    @Test
    fun `buildDeployment rendering env vars reference correct ports`() {
        val deployment = builder.buildDeployment()
        val containers = deployment.spec.template.spec.containers
        val grafanaContainer = containers.first { it.name == "grafana" }
        val rendererContainer = containers.first { it.name == "grafana-image-renderer" }

        val rendererPort = rendererContainer.ports.first().containerPort
        val grafanaPort = grafanaContainer.ports.first().containerPort
        val envMap = grafanaContainer.env.associate { it.name to it.value }

        assertThat(envMap["GF_RENDERING_SERVER_URL"]).contains(":$rendererPort/")
        assertThat(envMap["GF_RENDERING_CALLBACK_URL"]).contains(":$grafanaPort/")
    }

    @Test
    fun `buildDeployment does not attempt to install the bundled Pyroscope core plugin`() {
        // Regression: grafana-pyroscope-datasource is a BUNDLED core plugin in Grafana 13.x.
        // Listing it in GF_INSTALL_PLUGINS makes the boot-time install fail
        // ("cannot install a Core plugin") and Grafana CrashLoopBackOffs. Only externally
        // distributed plugins may appear here.
        val plugins =
            grafanaContainer()
                .env
                .first { it.name == "GF_INSTALL_PLUGINS" }
                .value
                .split(",")

        assertThat(plugins).doesNotContain("grafana-pyroscope-datasource")
        assertThat(plugins).contains(
            "grafana-clickhouse-datasource",
            "victoriametrics-logs-datasource",
            "grafana-polystat-panel",
        )
    }

    @Test
    fun `buildDeployment mounts the data hostPath that holds the dashboard tree and nothing per dashboard`() {
        val deployment = builder.buildDeployment()
        val volumes = deployment.spec.template.spec.volumes
        val mounts = grafanaContainer().volumeMounts

        val data = volumes.first { it.name == "data" }
        assertThat(data.hostPath.path).isEqualTo(GrafanaManifestBuilder.GRAFANA_DATA_PATH)
        assertThat(mounts.first { it.name == "data" }.mountPath).isEqualTo("/var/lib/grafana")

        assertThat(volumes.map { it.name }).containsExactlyInAnyOrder("datasources", "dashboards-config", "data")
        assertThat(mounts.map { it.name }).containsExactlyInAnyOrder("datasources", "dashboards-config", "data")
    }
}
