package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Presto, Trino and Flink serve metrics on a `hostPort` of the one node their pod runs on. A static
 * scrape job has every collector in the DaemonSet scrape `localhost:<port>`, so every other node
 * reports the job down (`up == 0`). Their scrape entries therefore use pod discovery, which only the
 * collector on the pod's own node acts on.
 */
class HostPortKitScrapeTest : BaseKoinTest() {
    private fun fixture(kit: String) =
        BuiltinKitFixture(kit, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))

    @ParameterizedTest
    @ValueSource(strings = ["presto", "trino", "flink"])
    fun `a kit whose metrics port is a hostPort is scraped by pod discovery, not at localhost on every node`(kit: String) {
        val scrapes = fixture(kit).scrapeMetrics

        assertThat(scrapes).isNotEmpty()
        assertThat(scrapes).allSatisfy { scrape -> assertThat(scrape.podSelector).isNotBlank() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["presto", "trino"])
    fun `the helm kits scrape their coordinator pod, the one the hostPort is patched onto`(kit: String) {
        val scrape = fixture(kit).scrapeMetrics.single()

        assertThat(parseLabelSelector(scrape.podSelector)).isEqualTo(
            mapOf("app.kubernetes.io/name" to kit, "app.kubernetes.io/component" to "coordinator"),
        )
    }

    @Test
    fun `flink scrapes the pods its runtime selects, the JobManager and every TaskManager`() {
        val fixture = fixture("flink")

        assertThat(fixture.scrapeMetrics.single().podSelector).isEqualTo(fixture.config.runtime?.selector)
    }
}
