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
 * Uses real TemplateService (never mocked per project convention) for provisioning
 * config. Dashboard JSON is loaded directly from classpath (no substitution).
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
    fun `buildDashboardConfigMap loads JSON and creates ConfigMap for each dashboard`() {
        GrafanaDashboard.entries.forEach { dashboard ->
            val configMap = builder.buildDashboardConfigMap(dashboard)

            assertThat(configMap.metadata.name)
                .describedAs("ConfigMap name for ${dashboard.name}")
                .isEqualTo(dashboard.configMapName)
            assertThat(configMap.data).containsKey(dashboard.jsonFileName)
            assertThat(configMap.data[dashboard.jsonFileName])
                .describedAs("JSON content for ${dashboard.name}")
                .isNotBlank()
        }
    }

    @Test
    fun `buildDashboardConfigMap loads raw JSON without template substitution`() {
        GrafanaDashboard.entries.forEach { dashboard ->
            val configMap = builder.buildDashboardConfigMap(dashboard)
            val json = configMap.data[dashboard.jsonFileName]!!

            // Dashboards are pure standard Grafana JSON - no __KEY__ placeholders allowed
            assertThat(json)
                .describedAs("Dashboard ${dashboard.name} should have no __KEY__ placeholders")
                .doesNotContainPattern("__[A-Z_]+__")
        }
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
}
