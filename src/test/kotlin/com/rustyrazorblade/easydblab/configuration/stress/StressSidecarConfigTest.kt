package com.rustyrazorblade.easydblab.configuration.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [StressSidecarConfig] owns the OTel sidecar's collector config and the ConfigMap that carries it
 * into the stress namespace. The real config resource is rendered; only the cluster API is mocked.
 */
class StressSidecarConfigTest : BaseKoinTest() {
    private val k8sService: K8sService = mock()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> {
                    mock<ClusterStateManager>().also {
                        whenever(
                            it.load(),
                        ).thenReturn(ClusterState(name = "test", s3Bucket = "easy-db-lab-test"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private fun sidecarConfig() = StressSidecarConfig(getKoin().get(), k8sService)

    private fun cluster(namespace: String? = null) =
        StressCluster.from(ClusterState(name = "test", s3Bucket = "easy-db-lab-test", initConfig = InitConfig(namespace = namespace)))

    @Test
    fun `renders the collector config the sidecar runs`() {
        val rendered = sidecarConfig().render(cluster())

        assertThat(rendered).contains("receivers:").contains("exporters:")
    }

    @Test
    fun `the sidecar detects its resource from the environment and exports to its node's core collector Service`() {
        val rendered = sidecarConfig().render(cluster())

        assertThat(rendered)
            .contains("detectors: [env]")
            .contains("processors: [resource_detection, batch]")
        // Exported to the node's core collector through its Service, not the node IP: in a
        // restricted environment no collector listens on the node.
        assertThat(rendered).contains("endpoint: otel-collector.default.svc:4317").doesNotContain("HOST_IP")
    }

    @Test
    fun `in another namespace the sidecar exports to that namespace's collector Service`() {
        assertThat(sidecarConfig().render(cluster("lab-a")))
            .contains("endpoint: otel-collector.lab-a.svc:4317")
            .doesNotContain(".default.svc")
    }

    @Test
    fun `creates the ConfigMap in the stress namespace holding the rendered config under the file name the Job runs`() {
        whenever(k8sService.createConfigMap(any(), any(), any(), any())).thenReturn(Result.success(Unit))

        val config = sidecarConfig()
        config.ensureConfigMap(cluster())

        val data = argumentCaptor<Map<String, String>>()
        verify(k8sService).createConfigMap(
            eq(TestScopes.NAMESPACE),
            eq(StressSidecarConfig.CONFIG_MAP_NAME),
            data.capture(),
            any(),
        )
        assertThat(data.firstValue).containsOnlyKeys(StressSidecarConfig.FILE_NAME)
        assertThat(data.firstValue.getValue(StressSidecarConfig.FILE_NAME)).isEqualTo(config.render(cluster()))
    }

    @Test
    fun `fails when the ConfigMap cannot be created`() {
        whenever(k8sService.createConfigMap(any(), any(), any(), any())).thenReturn(Result.failure(IllegalStateException("denied")))

        assertThatThrownBy { sidecarConfig().ensureConfigMap(cluster()) }.hasMessage("denied")
    }
}
