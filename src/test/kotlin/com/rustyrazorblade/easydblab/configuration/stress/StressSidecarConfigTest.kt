package com.rustyrazorblade.easydblab.configuration.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
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
                        ).thenReturn(ClusterState(name = "test"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private fun sidecarConfig() = StressSidecarConfig(getKoin().get(), k8sService)

    @Test
    fun `renders the collector config the sidecar runs`() {
        val rendered = sidecarConfig().render()

        assertThat(rendered).contains("receivers:").contains("exporters:")
    }

    @Test
    fun `creates the ConfigMap in the stress namespace holding the rendered config under the file name the Job runs`() {
        whenever(k8sService.createConfigMap(any(), any(), any(), any())).thenReturn(Result.success(Unit))

        val config = sidecarConfig()
        config.ensureConfigMap()

        val data = argumentCaptor<Map<String, String>>()
        verify(k8sService).createConfigMap(
            eq(Constants.Stress.NAMESPACE),
            eq(StressSidecarConfig.CONFIG_MAP_NAME),
            data.capture(),
            any(),
        )
        assertThat(data.firstValue).containsOnlyKeys(StressSidecarConfig.FILE_NAME)
        assertThat(data.firstValue.getValue(StressSidecarConfig.FILE_NAME)).isEqualTo(config.render())
    }

    @Test
    fun `fails when the ConfigMap cannot be created`() {
        whenever(k8sService.createConfigMap(any(), any(), any(), any())).thenReturn(Result.failure(IllegalStateException("denied")))

        assertThatThrownBy { sidecarConfig().ensureConfigMap() }.hasMessage("denied")
    }
}
