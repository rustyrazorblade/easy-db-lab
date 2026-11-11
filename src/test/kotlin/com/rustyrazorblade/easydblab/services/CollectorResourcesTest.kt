package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
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
        assertThat(config(NodeCapabilities.AWS)).contains("job_name: \"yace\"").contains("localhost:5001")
        assertThat(config(NodeCapabilities.LOCAL_DOCKER)).doesNotContain("yace").doesNotContain("5001")
    }
}
