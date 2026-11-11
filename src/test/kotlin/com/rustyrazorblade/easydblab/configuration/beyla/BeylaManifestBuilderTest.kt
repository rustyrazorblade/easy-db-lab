package com.rustyrazorblade.easydblab.configuration.beyla

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Guards the Beyla configuration the cluster renders.
 *
 * Beyla 3.36.0 injects a Java agent into every JVM it instruments unless `javaagent.enabled` is
 * false: it writes `obi-java-agent.jar` into the process's temp directory and dynamically attaches
 * it. On a Cassandra node that puts a foreign agent inside the database under test, and nothing in
 * the stack consumes what the agent produces.
 */
class BeylaManifestBuilderTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "acct"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private fun renderedConfig(): String {
        val templates: TemplateService = getKoin().get()
        return BeylaManifestBuilder(templates).buildConfigMap().data.getValue("beyla-config.yaml")
    }

    @Test
    fun `Beyla never injects a Java agent into a JVM`() {
        assertThat(scalarAt(renderedConfig(), "javaagent", "enabled")).isEqualTo("false")
    }
}
