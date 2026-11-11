package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.Service
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * ClickHouse, Kafka, Postgres and TiDB used to scrape their metrics through a NodePort: a static
 * `localhost:<nodePort>` job that every collector in the DaemonSet runs, so a single-instance kit
 * produced one duplicate series per node, each stamped with a different collector's hostname. Their
 * scrape entries now use pod discovery, which only the collector on the pod's own node acts on.
 *
 * The NodePort Services stay (for clients), and they are the record of which pods and which
 * container port serve the metrics, so each scrape is checked against the Service it replaced.
 */
class NodePortKitScrapeTest : BaseKoinTest() {
    private fun fixture(kit: String) =
        BuiltinKitFixture(kit, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))

    @ParameterizedTest
    @MethodSource("kitServiceTemplates")
    fun `each scrape targets the pods and container port of the NodePort Service that used to serve it`(
        kit: String,
        serviceTemplates: List<String>,
        jobs: List<String>,
    ) {
        val fixture = fixture(kit)
        val services = serviceTemplates.flatMap { fixture.render(it, TEMPLATE_ARGS) }.filterIsInstance<Service>()
        val scrapes = fixture.scrapeMetrics.filter { it.job.ifBlank { kit } in jobs }

        assertThat(scrapes.map { it.job.ifBlank { kit } }).containsExactlyInAnyOrderElementsOf(jobs)
        assertThat(scrapes).allSatisfy { scrape ->
            // The fixture renders templates for the instance named after the kit itself.
            val selector = parseLabelSelector(scrape.podSelector.replace("\${KIT_NAME}", kit))
            assertThat(selector).isNotEmpty()
            assertThat(services).anySatisfy { service ->
                assertThat(service.spec.selector).isEqualTo(selector)
                assertThat(service.spec.ports.map { it.targetPort.intVal }).contains(scrape.port)
            }
        }
    }

    /**
     * No built-in kit may declare a static scrape: a static job is run by every collector in the
     * DaemonSet, whatever node the workload is on.
     */
    @Test
    fun `no built-in kit declares a static scrape job`() {
        // Main and test resources both have a kits directory; the built-in kits are in main.
        val kits =
            javaClass.classLoader
                .getResources(KITS_RESOURCE_DIR)
                .toList()
                .flatMap { url -> File(url.toURI()).listFiles { dir -> File(dir, "kit.yaml").isFile }.orEmpty().toList() }
                .map { it.name }
                .distinct()

        val staticScrapes =
            kits.flatMap { kit ->
                fixture(kit).scrapeMetrics.filter { it.podSelector.isBlank() }.map { "$kit:${it.port}" }
            }

        assertThat(kits).contains("clickhouse", "kafka", "postgres", "tidb")
        assertThat(staticScrapes).isEmpty()
    }

    companion object {
        private const val KITS_RESOURCE_DIR = "com/rustyrazorblade/easydblab/kits"

        /** Postgres's NodePort template takes its ports from the extension, not a kit arg. */
        private val TEMPLATE_ARGS = mapOf("POSTGRES_PORT" to "30432", "METRICS_PORT" to "30987")

        @JvmStatic
        fun kitServiceTemplates(): List<Arguments> =
            listOf(
                Arguments.of("clickhouse", listOf("nodeport-service.yaml.template"), listOf("clickhouse")),
                Arguments.of(
                    "kafka",
                    listOf("kafka-exporter-nodeport.yaml.template", "kafka-broker-jmx-nodeport.yaml.template"),
                    listOf("kafka-exporter", "kafka-jmx"),
                ),
                Arguments.of("postgres", listOf("nodeport-service.yaml.template"), listOf("postgres")),
                // TiKV has always been pod-discovered; it has no NodePort Service.
                Arguments.of("tidb", listOf("nodeport-service.yaml.template"), listOf("tidb-sql", "pd", "tiflash")),
            )
    }
}
