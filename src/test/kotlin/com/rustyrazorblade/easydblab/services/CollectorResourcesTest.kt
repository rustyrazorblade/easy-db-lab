package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** The collector config both the deploy and the kit sync render must follow the environment's capabilities. */
class CollectorResourcesTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager> { on { load() } doReturn state } }
                single { TemplateService(get(), get()) }
            },
        )

    private val host = ClusterHost("1.2.3.4", "10.0.0.1", "control0", "us-west-2a")
    private val state = ClusterState(name = "test", versions = mutableMapOf())

    private fun config(capabilities: NodeCapabilities): String {
        val resources: List<HasMetadata> =
            CollectorResources.build(OtelManifestBuilder(getKoin().get()), host, state, "us-west-2", emptyList(), capabilities)
        return checkNotNull(resources.filterIsInstance<ConfigMap>().single().data["otel-collector-config.yaml"])
    }

    @Test
    fun `the yace scrape job exists only with CloudWatch`() {
        assertThat(config(NodeCapabilities.AWS)).contains("job_name: \"yace\"").contains("__meta_kubernetes_pod_node_name")
        assertThat(config(NodeCapabilities.LOCAL_DOCKER)).doesNotContain("yace").doesNotContain("5001")
    }

    @Test
    fun `pod logs are collected only where the environment grants hostPath volumes`() {
        val withHostPath = NodeCapabilities(setOf(NodeCapability.HOST_PATH))
        val withoutHostPath = NodeCapabilities(NodeCapability.entries.toSet() - NodeCapability.HOST_PATH)
        assertThat(config(withHostPath)).contains("file_log/containers").contains("logs/containers:")
        assertThat(config(withoutHostPath)).doesNotContain("file_log").doesNotContain("logs/containers")
        assertThat(
            daemonSet(withoutHostPath)
                .spec.template.spec.volumes
                .mapNotNull { it.hostPath },
        ).isEmpty()
    }

    private fun daemonSet(capabilities: NodeCapabilities): DaemonSet =
        CollectorResources
            .build(OtelManifestBuilder(getKoin().get()), host, state, "us-west-2", emptyList(), capabilities)
            .filterIsInstance<DaemonSet>()
            .single()
}
