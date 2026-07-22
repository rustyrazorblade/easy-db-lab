package com.rustyrazorblade.easydblab.configuration.pyroscope

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
 * Tests for PyroscopeManifestBuilder.
 *
 * Uses real TemplateService (never mocked per project convention) to verify
 * config file loading from classpath resources.
 */
class PyroscopeManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: PyroscopeManifestBuilder
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
        builder = PyroscopeManifestBuilder(templateService)
    }

    @Test
    fun `buildServerDeployment has no init container`() {
        val deployment = builder.buildServerDeployment()

        assertThat(deployment.spec.template.spec.initContainers).isNullOrEmpty()
    }

    @Test
    fun `eBPF config discovers Kubernetes pods and derives per-pod attribution labels`() {
        val configMap = builder.buildEbpfConfigMap()
        val config = configMap.data.getValue("config.alloy")

        // Discovers pods on the local node so processes can be attributed.
        assertThat(config).contains("discovery.kubernetes \"pods\"")
        assertThat(config).contains("spec.nodeName=")
        // Joins host processes to pod metadata by container id.
        assertThat(config).contains("join")
        assertThat(config).contains("discovery.relabel.kubernetes_pods.output")
        // Produces the dimensions Pyroscope filters on.
        assertThat(config).contains("target_label  = \"service_name\"")
        assertThat(config).contains("target_label  = \"pod\"")
        assertThat(config).contains("target_label  = \"container\"")
        assertThat(config).contains("__meta_kubernetes_pod_container_name")
    }

    @Test
    fun `eBPF DaemonSet uses the pod-reading ServiceAccount`() {
        val daemonSet = builder.buildEbpfDaemonSet()

        assertThat(daemonSet.spec.template.spec.serviceAccountName).isEqualTo("pyroscope-ebpf")
    }

    @Test
    fun `eBPF ClusterRole grants read access to pods for discovery`() {
        val clusterRole = builder.buildEbpfClusterRole()

        val podRule =
            clusterRole.rules.single { rule ->
                rule.resources.contains("pods")
            }
        assertThat(podRule.verbs).contains("get", "list", "watch")
    }
}
