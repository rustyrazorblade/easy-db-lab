package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.access.KitEndpointServices
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every endpoint of every packaged kit is published on a Service its own files name: the Service a
 * rendered manifest declares with the endpoint's NodePort, or one its `kit.yaml` entry names
 * explicitly for a Service a chart or an operator creates. A port-forward targets that Service, so
 * an endpoint resolved to no Service, or to a guessed one, cannot be reached on a port-forward cluster.
 */
class KitEndpointServiceTest : BaseKoinTest() {
    private val resolver get() = InstallTemplateResolver(context, KitSourcesProvider(context))

    private fun fixture(kit: String) =
        BuiltinKitFixture(kit, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))

    /** The kit's Service manifests, rendered with its arg defaults into a directory as `kit install` writes them. */
    private fun renderedKitDir(kit: String): File {
        val dir = File(tempDir, "kits/$kit").apply { mkdirs() }
        val manifest = Regex("""\.ya?ml\.template$""")
        resolver
            .listTemplateFiles(InstallTemplateResolver.TemplateSource.Builtin(kit))
            .filter { manifest.containsMatchIn(it.name) && it.content.contains("kind: Service") }
            .forEach { entry ->
                File(dir, entry.name.removeSuffix(".template")).apply {
                    parentFile.mkdirs()
                    writeText(fixture(kit).renderText(entry.name, INSTALL_VARS[kit].orEmpty()))
                }
            }
        return dir
    }

    @Test
    fun `every packaged kit endpoint resolves to a Service and port its manifests declare or its kit yaml names`() {
        val kits = resolver.listAvailableTemplates()
        assertThat(kits).contains("clickhouse", "flink", "ignite3", "kafka", "presto")

        assertSoftly { softly ->
            kits.forEach { kit ->
                val config = fixture(kit).config
                val dir = renderedKitDir(kit)
                val fromManifests = KitEndpointServices.read(dir, config.copy(endpoints = emptyList()))
                val services = KitEndpointServices.read(dir, config)
                config.endpoints.forEach { endpoint ->
                    val service = services.of(endpoint)
                    softly
                        .assertThat(service?.port)
                        .`as`("$kit endpoint '${endpoint.name}' (port ${endpoint.port}) Service ${service?.name}")
                        .isNotNull()
                    if (endpoint.service.isNotBlank()) {
                        // A name in kit.yaml is only for a Service no manifest of the kit declares.
                        softly
                            .assertThat(fromManifests.of(endpoint.copy(service = "", servicePort = null))?.takeIf { it.port != null })
                            .`as`("$kit endpoint '${endpoint.name}' names a Service its manifests already declare")
                            .isNull()
                    }
                }
            }
        }
    }

    private companion object {
        /** Variables `kit install` derives rather than reads from arg defaults: the base postgres instance's NodePorts. */
        val INSTALL_VARS = mapOf("postgres" to mapOf("POSTGRES_PORT" to "30432", "METRICS_PORT" to "30987"))
    }
}
