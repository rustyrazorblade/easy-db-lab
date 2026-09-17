package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.Yaml
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
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
 * Uses real TemplateService (never mocked per project convention) and the real discovered
 * dashboard catalog, so every assertion runs against the dashboards that actually ship.
 */
class GrafanaManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: GrafanaManifestBuilder
    private lateinit var templateService: TemplateService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private val catalog = GrafanaDashboardCatalog.discover()

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
        builder = GrafanaManifestBuilder(templateService, catalog)
    }

    private fun provisioningConfig(): GrafanaDashboardProvisioningConfig {
        val yaml =
            checkNotNull(builder.buildDashboardProvisioningConfigMap().data["dashboards.yaml"]) {
                "provisioning ConfigMap has no dashboards.yaml key"
            }
        return Yaml.default.decodeFromString(GrafanaDashboardProvisioningConfig.serializer(), yaml)
    }

    private fun grafanaContainer() =
        builder
            .buildDeployment()
            .spec.template.spec.containers
            .first { it.name == "grafana" }

    /**
     * The check that would have caught a folder mounted where nothing was watching.
     *
     * A dashboard mounted outside every provider's path is a silent failure: the ConfigMap is
     * created, the volume mounts, Grafana starts, and the dashboard simply never appears. Nothing
     * in the deploy reports it. So the provisioning providers and the mount paths are compared
     * directly here.
     */
    @Test
    fun `every discovered folder is backed by a provider watching that exact path`() {
        val providerPaths = provisioningConfig().providers.map { it.options.path }

        assertThat(providerPaths).containsExactlyElementsOf(catalog.folders.map { GrafanaDashboard.folderProviderPath(it) })
        assertThat(catalog.dashboards).allSatisfy { dashboard ->
            assertThat(dashboard.mountPath)
                .describedAs("mount path for ${dashboard.resourcePath}")
                .startsWith("${dashboard.folderPath}/")
            assertThat(providerPaths)
                .describedAs("no provider watches ${dashboard.folderPath}, where ${dashboard.resourcePath} mounts")
                .contains(dashboard.folderPath)
        }
    }

    @Test
    fun `the home dashboard resolves inside its folder`() {
        // GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH is built from the home dashboard's mount path.
        // Left pointing anywhere else, Grafana opens on an empty page.
        val homePath = grafanaContainer().env.first { it.name == "GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH" }.value

        assertThat(homePath).isEqualTo("/var/lib/grafana/dashboards-infrastructure/system-overview/system-overview.json")
    }

    @Test
    fun `buildAllResources deploys every discovered dashboard plus provisioning and deployment`() {
        val names = builder.buildAllResources().map { it.metadata.name }

        assertThat(names).containsAll(catalog.dashboards.map { it.configMapName })
        assertThat(names).contains("grafana-dashboards-config", "grafana")
        assertThat(names).hasSize(catalog.dashboards.size + 2)
    }

    @Test
    fun `buildDashboardConfigMap preserves Grafana built-in variables`() {
        val dashboard = GrafanaDashboard("cassandra", "cassandra-overview.json")

        val json = builder.buildDashboardConfigMap(dashboard).data[dashboard.jsonFileName]!!

        assertThat(json).contains("\$__rate_interval")
    }

    @Test
    fun `the profiling dashboard gets the Pyroscope URL substituted`() {
        val profiling =
            builder.buildAllResources(pyroscopeUrl = "http://10.0.0.1:4040").first {
                it.metadata.name ==
                    "grafana-dashboard-profiling"
            }

        val json = profiling.asConfigMapData("profiling.json")
        assertThat(json).contains("http://10.0.0.1:4040")
        assertThat(json).doesNotContain("__PYROSCOPE_URL__")
    }

    @Test
    fun `other dashboards are deployed verbatim even when a Pyroscope URL is given`() {
        val overview =
            builder.buildAllResources(pyroscopeUrl = "http://10.0.0.1:4040").first {
                it.metadata.name ==
                    "grafana-dashboard-cassandra-overview"
            }

        assertThat(overview.asConfigMapData("cassandra-overview.json"))
            .isEqualTo(javaClass.getResource("/dashboards/cassandra/cassandra-overview.json")!!.readText())
    }

    private fun HasMetadata.asConfigMapData(key: String): String = (this as ConfigMap).data.getValue(key)

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
    fun `buildDeployment mounts every dashboard read-only at its mount path`() {
        val container = grafanaContainer()

        catalog.dashboards.forEach { dashboard ->
            val mount = container.volumeMounts.find { it.name == dashboard.volumeName }
            assertThat(mount)
                .describedAs("Volume mount for ${dashboard.resourcePath}")
                .isNotNull
            assertThat(mount!!.mountPath).isEqualTo(dashboard.mountPath)
            assertThat(mount.readOnly).isTrue()
        }
    }

    @Test
    fun `buildDeployment backs every dashboard volume with a required ConfigMap`() {
        // No dashboard is optional any more: every one the catalog found exists by construction,
        // so a missing ConfigMap is a deploy bug that should stop the pod, not be papered over.
        val volumes =
            builder
                .buildDeployment()
                .spec.template.spec.volumes

        catalog.dashboards.forEach { dashboard ->
            val volume = volumes.find { it.name == dashboard.volumeName }
            assertThat(volume)
                .describedAs("Volume for ${dashboard.resourcePath}")
                .isNotNull
            assertThat(volume!!.configMap.name).isEqualTo(dashboard.configMapName)
            assertThat(volume.configMap.optional == true)
                .describedAs("${dashboard.resourcePath} must not be an optional volume")
                .isFalse()
        }
    }
}
