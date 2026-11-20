package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.access.KitEndpointServices
import com.rustyrazorblade.easydblab.services.sql.KitService
import io.fabric8.kubernetes.api.model.Service
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/**
 * The presto and trino coordinators are published on a NodePort Service of the kit's own, not a
 * hostPort: every endpoint (UI and JDBC, so `sql` too) resolves to that Service, which selects the
 * coordinator the kit scrapes and serves its HTTP port.
 */
class SqlEngineKitServiceTest : BaseKoinTest() {
    private fun fixture(kit: String) =
        BuiltinKitFixture(kit, TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))

    @ParameterizedTest
    @CsvSource("presto", "trino")
    fun `every endpoint resolves to the coordinator's NodePort Service on its HTTP port`(kit: String) {
        val fixture = fixture(kit)
        val nodePort = NODE_PORTS.getValue(kit)
        val dir = File(tempDir, kit).apply { mkdirs() }
        File(dir, "nodeport-service.yaml").writeText(fixture.renderText("nodeport-service.yaml.template"))
        val service = fixture.render("nodeport-service.yaml.template").filterIsInstance<Service>().single()

        val coordinator = parseLabelSelector(fixture.scrapeMetrics.single().podSelector)
        assertThat(service.spec.type).isEqualTo("NodePort")
        assertThat(service.spec.selector).containsAllEntriesOf(coordinator)
        assertThat(
            service.spec.ports
                .single()
                .nodePort,
        ).isEqualTo(nodePort)

        val services = KitEndpointServices.read(dir, fixture.config)
        assertThat(fixture.config.endpoints).isNotEmpty.allSatisfy { endpoint ->
            assertThat(endpoint.port).isEqualTo(nodePort)
            assertThat(services.of(endpoint)).isEqualTo(KitService("default", "$kit-nodeport", HTTP_PORT))
        }
    }

    private companion object {
        const val HTTP_PORT = 8080
        val NODE_PORTS =
            mapOf(
                "presto" to Constants.Kit.Presto.NODE_PORT,
                "trino" to Constants.Kit.Trino.NODE_PORT,
            )
    }
}
