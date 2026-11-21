package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServicePublishing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests for the profiling documents exchanged between the CLI and each node's reconciler:
 * the desired-state [ProfilingConfig] the CLI writes, and the effective state the reconciler
 * rewrites at the end of every pass.
 */
class ProfilingConfigTest {
    private val config =
        ProfilingConfig(
            enabled = true,
            asprofArgs = listOf("-e", "cpu", "--include", "org.apache.cassandra spaced"),
            loopInterval = "1m",
            retentionMinutes = 60,
            maxBytes = 2147483648L,
            pyroscopeUrl = "http://10.0.1.5:4040",
            clusterName = "test-cluster",
            updatedAt = "2026-08-24T10:15:30Z",
        )

    @Test
    fun `round-trips preserving the exact argument tokenization`() {
        val decoded = parseProfilingConfig(config.toJson())

        assertThat(decoded).isEqualTo(config)
        assertThat(decoded?.asprofArgs).containsExactly("-e", "cpu", "--include", "org.apache.cassandra spaced")
    }

    @Test
    fun `carries the cluster's tenant to the node`() {
        val withTenant = config.copy(tenant = "acme")

        assertThat(withTenant.toJson()).contains("\"tenant\": \"acme\"")
        assertThat(parseProfilingConfig(withTenant.toJson())?.tenant).isEqualTo("acme")
    }

    @Test
    fun `a document written before tenants existed reads as the default tenant`() {
        val legacy = """{"enabled": true, "asprofArgs": ["-e", "cpu"]}"""

        assertThat(parseProfilingConfig(legacy)?.tenant).isEqualTo("default")
    }

    @Test
    fun `encodes arguments as a JSON array so the node never re-splits them`() {
        assertThat(config.toJson()).contains("\"asprofArgs\"")
        // A single string would force the node to re-split; an array does not.
        assertThat(parseProfilingConfig(config.toJson())?.asprofArgs).hasSize(4)
    }

    @Test
    fun `an in-cluster producer sends to the Pyroscope Service`() {
        assertThat(pyroscopeIngestBaseUrl(telemetryRedirect = null))
            .isEqualTo("http://pyroscope.default.svc:${Constants.K8s.PYROSCOPE_PORT}")
    }

    @Test
    fun `a host process sends to the node-local relay on loopback`() {
        assertThat(nodeLocalProfilesRelay()).isEqualTo("http://127.0.0.1:${Constants.K8s.ALLOY_RELAY_PORT}")
    }

    @Test
    fun `parses an effective-state document written by the reconciler`() {
        val document =
            """
            {
              "running": true,
              "pid": 4242,
              "args": ["-e", "wall"],
              "startedAt": 1756000000,
              "chunksPending": 2,
              "chunksShipped": 17,
              "chunksRejected": 1,
              "shipFailures": 3,
              "bytesOnDisk": 987654,
              "lastError": "http_500",
              "updatedAt": 1756000060
            }
            """.trimIndent()

        val state = parseProfilingEffectiveState(document)

        assertThat(state?.running).isTrue()
        assertThat(state?.pid).isEqualTo(4242L)
        assertThat(state?.args).containsExactly("-e", "wall")
        assertThat(state?.chunksPending).isEqualTo(2)
        assertThat(state?.chunksRejected).isEqualTo(1L)
        assertThat(state?.shipFailures).isEqualTo(3L)
        assertThat(state?.bytesOnDisk).isEqualTo(987654L)
        assertThat(state?.lastError).isEqualTo("http_500")
    }

    @Test
    fun `tolerates a truncated effective-state document`() {
        assertThat(parseProfilingEffectiveState("""{"running": true, "pid": 42""")).isNull()
    }

    @Test
    fun `tolerates a malformed effective-state document`() {
        assertThat(parseProfilingEffectiveState("not json at all")).isNull()
    }

    @Test
    fun `tolerates an empty effective-state document`() {
        assertThat(parseProfilingEffectiveState("")).isNull()
        assertThat(parseProfilingEffectiveState("   \n ")).isNull()
    }

    @Test
    fun `tolerates an effective-state document missing optional fields`() {
        val state = parseProfilingEffectiveState("""{"running": false, "pid": 0}""")

        assertThat(state?.running).isFalse()
        assertThat(state?.args).isEmpty()
        assertThat(state?.lastError).isEmpty()
    }

    @Test
    fun `tolerates a malformed desired-state document`() {
        assertThat(parseProfilingConfig("{")).isNull()
        assertThat(parseProfilingConfig("")).isNull()
    }

    @Test
    fun `the browser reaches Pyroscope on the control node's NodePort where NodePorts are published`() {
        val control = ClusterHost(publicIp = "54.1.2.3", privateIp = "10.0.1.5", alias = "control0", availabilityZone = "us-west-2a")

        assertThat(pyroscopeBrowserUrl(ServicePublishing.NodePorts, control))
            .isEqualTo("http://10.0.1.5:${Constants.K8s.PYROSCOPE_NODE_PORT}")
    }

    @Test
    fun `NodePort publishing with no control node fails, naming the need`() {
        assertThatThrownBy { pyroscopeBrowserUrl(ServicePublishing.NodePorts, null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("control node")
            .hasMessageContaining("Pyroscope")
    }

    @Test
    fun `without NodePorts the browser link names the Pyroscope Service`() {
        assertThat(pyroscopeBrowserUrl(ServicePublishing.ClusterIp, null))
            .isEqualTo("http://pyroscope.default.svc:${Constants.K8s.PYROSCOPE_PORT}")
    }
}
