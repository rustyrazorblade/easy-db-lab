package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apps.Deployment
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
 * Uses real TemplateService (never mocked per project convention) to verify
 * dashboard JSON loading and template variable substitution.
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

    @Test
    fun `buildDashboardProvisioningConfigMap produces correct ConfigMap`() {
        val configMap = builder.buildDashboardProvisioningConfigMap()

        assertThat(configMap.metadata.name).isEqualTo("grafana-dashboards-config")
        assertThat(configMap.metadata.namespace).isEqualTo("default")
        assertThat(configMap.metadata.labels).containsEntry("app.kubernetes.io/name", "grafana")
        assertThat(configMap.data).containsKey("dashboards.yaml")
        assertThat(configMap.data["dashboards.yaml"]).contains("path: /var/lib/grafana/dashboards")
    }

    @Test
    fun `buildDashboardConfigMap loads JSON and creates ConfigMap for each dashboard`() {
        GrafanaDashboard.entries.forEach { dashboard ->
            val configMap = builder.buildDashboardConfigMap(dashboard)

            assertThat(configMap.metadata.name)
                .describedAs("ConfigMap name for ${dashboard.name}")
                .isEqualTo(dashboard.configMapName)
            assertThat(configMap.metadata.namespace).isEqualTo("default")
            assertThat(configMap.metadata.labels).containsEntry("grafana_dashboard", "1")
            assertThat(configMap.data).containsKey(dashboard.jsonFileName)
            assertThat(configMap.data[dashboard.jsonFileName])
                .describedAs("JSON content for ${dashboard.name}")
                .isNotBlank()
        }
    }

    @Test
    fun `buildDashboardConfigMap substitutes template variables`() {
        val configMap = builder.buildDashboardConfigMap(GrafanaDashboard.SYSTEM)
        val json = configMap.data[GrafanaDashboard.SYSTEM.jsonFileName]!!

        // Template variables should be substituted - no remaining __KEY__ placeholders
        assertThat(json).doesNotContain("__CLUSTER_NAME__")
        assertThat(json).doesNotContain("__BUCKET_NAME__")
    }

    @Test
    fun `buildDeployment creates correct deployment structure`() {
        val deployment = builder.buildDeployment()

        assertThat(deployment.metadata.name).isEqualTo("grafana")
        assertThat(deployment.metadata.namespace).isEqualTo("default")
        assertThat(deployment.spec.replicas).isEqualTo(1)
        assertThat(deployment.spec.strategy.type).isEqualTo("Recreate")
    }

    @Test
    fun `buildDeployment includes volume mounts for all dashboards`() {
        val deployment = builder.buildDeployment()
        val container =
            deployment.spec.template.spec.containers
                .first()

        GrafanaDashboard.entries.forEach { dashboard ->
            val mount = container.volumeMounts.find { it.name == dashboard.volumeName }
            assertThat(mount)
                .describedAs("Volume mount for ${dashboard.name}")
                .isNotNull
            assertThat(mount!!.mountPath).isEqualTo(dashboard.mountPath)
            assertThat(mount.readOnly).isTrue()
        }
    }

    @Test
    fun `buildDeployment includes volumes for all dashboards with correct optional flag`() {
        val deployment = builder.buildDeployment()
        val volumes = deployment.spec.template.spec.volumes

        GrafanaDashboard.entries.forEach { dashboard ->
            val volume = volumes.find { it.name == dashboard.volumeName }
            assertThat(volume)
                .describedAs("Volume for ${dashboard.name}")
                .isNotNull
            assertThat(volume!!.configMap.name).isEqualTo(dashboard.configMapName)
            assertThat(volume.configMap.optional)
                .describedAs("Optional flag for ${dashboard.name}")
                .isEqualTo(dashboard.optional)
        }
    }

    @Test
    fun `buildDeployment includes datasources and provisioning volumes`() {
        val deployment = builder.buildDeployment()
        val container =
            deployment.spec.template.spec.containers
                .first()
        val volumes = deployment.spec.template.spec.volumes

        // Datasources volume
        assertThat(volumes.find { it.name == "datasources" }).isNotNull
        assertThat(container.volumeMounts.find { it.mountPath == "/etc/grafana/provisioning/datasources" }).isNotNull

        // Dashboards config volume
        assertThat(volumes.find { it.name == "dashboards-config" }).isNotNull
        assertThat(container.volumeMounts.find { it.mountPath == "/etc/grafana/provisioning/dashboards" }).isNotNull

        // Data volume
        assertThat(volumes.find { it.name == "data" }).isNotNull
        assertThat(container.volumeMounts.find { it.mountPath == "/var/lib/grafana" }).isNotNull
    }

    @Test
    fun `buildDeployment includes required environment variables`() {
        val deployment = builder.buildDeployment()
        val envVars =
            deployment.spec.template.spec.containers
                .first()
                .env

        val envMap = envVars.associate { it.name to it.value }
        assertThat(envMap).containsKey("GF_INSTALL_PLUGINS")
        assertThat(envMap["GF_INSTALL_PLUGINS"]).contains("grafana-clickhouse-datasource")
        assertThat(envMap).containsKey("GF_SECURITY_ADMIN_USER")
        assertThat(envMap).containsKey("GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH")
        assertThat(envMap["GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH"])
            .isEqualTo("${GrafanaDashboard.SYSTEM.mountPath}/${GrafanaDashboard.SYSTEM.jsonFileName}")
    }

    @Test
    fun `buildDeployment runs on control plane node`() {
        val deployment = builder.buildDeployment()
        val podSpec = deployment.spec.template.spec

        assertThat(podSpec.nodeSelector).containsEntry("node-role.kubernetes.io/control-plane", "true")
        assertThat(podSpec.tolerations).anyMatch {
            it.key == "node-role.kubernetes.io/control-plane" && it.operator == "Exists"
        }
    }

    @Test
    fun `buildAllResources returns all resources in order`() {
        val resources = builder.buildAllResources()

        // 1 provisioning ConfigMap + 7 dashboard ConfigMaps + 1 Deployment = 9
        val expectedCount = 1 + GrafanaDashboard.entries.size + 1
        assertThat(resources).hasSize(expectedCount)

        // First should be the provisioning ConfigMap
        assertThat(resources.first()).isInstanceOf(ConfigMap::class.java)
        assertThat((resources.first() as ConfigMap).metadata.name).isEqualTo("grafana-dashboards-config")

        // Last should be the Deployment
        assertThat(resources.last()).isInstanceOf(Deployment::class.java)
        assertThat((resources.last() as Deployment).metadata.name).isEqualTo("grafana")
    }
}
