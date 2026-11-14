package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.KitEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StatusAddressesTest {
    private fun state(exposure: Exposure) =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            hosts =
                mapOf(
                    ServerType.Control to listOf(ClusterHost("54.0.0.1", "10.0.1.200", "control0", "us-west-2a", "i-c")),
                    ServerType.Cassandra to listOf(ClusterHost("54.0.0.2", "10.0.1.100", "db0", "us-west-2a", "i-d")),
                ),
        ).apply { this.exposure = exposure }

    private val cql = KitEndpoint(name = "CQL", nodeType = "db", port = 30942, type = KitEndpoint.EndpointType.NATIVE)

    @Test
    fun `on Socks and Direct a Service is shown at its node address, as before`() {
        listOf(Exposure.Socks, Exposure.Direct).forEach { exposure ->
            val addresses = StatusAddresses(state(exposure))

            assertThat(addresses.service(ClusterServices.GRAFANA)).isEqualTo(StatusAddress("http://10.0.1.200:3000"))
        }
    }

    @Test
    fun `on a port-forward cluster a Service is shown by its DNS name with a ready-to-run forward command`() {
        val shown = StatusAddresses(state(Exposure.PortForward)).service(ClusterServices.GRAFANA)

        assertThat(shown.address).isEqualTo("http://grafana.default.svc.cluster.local:3000")
        assertThat(shown.hint).isEqualTo("bin/kubectl port-forward -n default svc/grafana 3000")
    }

    @Test
    fun `a kit endpoint is shown on every host of its node type on Socks`() {
        val shown = StatusAddresses(state(Exposure.Socks)).kitEndpoint("cassandra-pods", null, cql)

        assertThat(shown).containsExactly(StatusAddress("10.0.1.100:30942"))
    }

    @Test
    fun `a kit endpoint on a port-forward cluster names the kit's Service and the command that forwards it`() {
        val shown = StatusAddresses(state(Exposure.PortForward)).kitEndpoint("cassandra-pods", null, cql)

        assertThat(shown).containsExactly(
            StatusAddress(
                "cassandra-pods-nodeport.default.svc.cluster.local:30942",
                "bin/kubectl port-forward -n default svc/cassandra-pods-nodeport 30942",
            ),
        )
    }

    @Test
    fun `the forward command maps the local NodePort to the Service port behind it`() {
        val http = KitEndpoint(name = "HTTP", nodeType = "db", port = 30123, type = KitEndpoint.EndpointType.HTTP)
        val addresses = StatusAddresses(state(Exposure.PortForward))

        val shown = addresses.kitEndpoint("clickhouse", null, http) { _, port -> if (port == 30123) 8123 else port }.single()

        assertThat(shown.address).isEqualTo("http://clickhouse-nodeport.default.svc.cluster.local:8123")
        assertThat(shown.hint).isEqualTo("bin/kubectl port-forward -n default svc/clickhouse-nodeport 30123:8123")
    }

    @Test
    fun `an installed kit's endpoints are read from its descriptor, and an unreadable one fails`(
        @TempDir workspace: File,
    ) {
        val kitDir = File(workspace, "cassandra-pods").apply { mkdirs() }
        File(kitDir, Constants.Kit.CONFIG_FILE).writeText(
            """
            name: cassandra-pods
            endpoints:
              - name: CQL
                node-type: db
                port: 30942
                type: native
            """.trimIndent(),
        )
        val broken = File(workspace, "broken").apply { mkdirs() }
        File(broken, Constants.Kit.CONFIG_FILE).writeText("name: [broken")
        val addresses = StatusAddresses(state(Exposure.PortForward))

        val shown = addresses.installedKit(kitDir).getOrThrow()

        assertThat(shown.map { it.endpoint.name to it.address.hint })
            .containsExactly("CQL" to "bin/kubectl port-forward -n default svc/cassandra-pods-nodeport 30942")
        assertThat(addresses.installedKit(broken).isFailure).isTrue()
    }

    @Test
    fun `an installed kit's NodePort is shown at the Service port its installed manifest maps it to`(
        @TempDir workspace: File,
    ) {
        val kitDir = File(workspace, "web").apply { mkdirs() }
        File(kitDir, Constants.Kit.CONFIG_FILE).writeText(
            """
            name: web
            endpoints:
              - name: HTTP
                node-type: db
                port: 31080
                type: http
              - name: Admin
                node-type: db
                port: 31090
                type: http
            """.trimIndent(),
        )
        File(kitDir, "services.yaml").writeText(
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: web-nodeport
              namespace: default
            ---
            apiVersion: v1
            kind: Service
            metadata:
              name: web-nodeport
              namespace: default
            spec:
              type: NodePort
              ports:
                - name: http
                  port: 8080
                  nodePort: 31080
            """.trimIndent(),
        )

        val shown = StatusAddresses(state(Exposure.PortForward)).installedKit(kitDir).getOrThrow()

        assertThat(shown.map { it.address }).containsExactly(
            StatusAddress(
                "http://web-nodeport.default.svc.cluster.local:8080",
                "bin/kubectl port-forward -n default svc/web-nodeport 31080:8080",
            ),
            // A port the manifest does not map is shown as it is.
            StatusAddress(
                "http://web-nodeport.default.svc.cluster.local:31090",
                "bin/kubectl port-forward -n default svc/web-nodeport 31090",
            ),
        )
    }

    @Test
    fun `the observability backends are the five Services, Mimir under its Prometheus path`() {
        val shown = StatusAddresses(state(Exposure.Socks)).observability()

        assertThat(shown.grafana.address).isEqualTo("http://10.0.1.200:3000")
        assertThat(shown.mimir.address).isEqualTo("http://10.0.1.200:9009/prometheus")
        assertThat(shown.loki.address).isEqualTo("http://10.0.1.200:3100")
        assertThat(shown.tempo.address).isEqualTo("http://10.0.1.200:3200")
        assertThat(shown.pyroscope.address).isEqualTo("http://10.0.1.200:4040")
    }
}
