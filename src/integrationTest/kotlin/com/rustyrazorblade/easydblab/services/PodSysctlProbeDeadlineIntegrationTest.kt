package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Proves that a probe [PodNetworkTuning] cuts off at its deadline does not linger: the real
 * [Fabric8PodSysctlProbe] talks to an API server that accepts the connection and never answers, and
 * the test checks that the client closes that connection soon after `apply()` gives up, long before
 * the client's own request timeout.
 */
class PodSysctlProbeDeadlineIntegrationTest {
    @Test
    fun `a probe cut off at the deadline closes its connection instead of waiting out the request timeout`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = ACCEPT_TIMEOUT_MS
            val closedByClient =
                CompletableFuture.supplyAsync {
                    server.accept().use { connection -> drainUntilClosed(connection.getInputStream()) }
                }
            val tuning =
                PodNetworkTuning(
                    RecordingManifests(),
                    Fabric8PodSysctlProbe(WorkspaceK8s.provider(silentServerKubeconfig(server.localPort))) { TestScopes.EC2.namespace },
                    EventBus(),
                    mapOf("net.core.somaxconn" to "65535"),
                    Duration.ofSeconds(1),
                    Duration.ofMillis(100),
                )

            assertThatThrownBy { tuning.apply() }
                .isInstanceOf(CommandFailedException::class.java)
                .hasMessageContaining("no answer before the 1s deadline")

            assertThat(closedByClient.get(CLOSE_WITHIN_SECONDS, TimeUnit.SECONDS)).isTrue()
        }
    }

    /** Reads and drops the request until the client closes the connection; true when it did. */
    private fun drainUntilClosed(input: InputStream): Boolean {
        val buffer = ByteArray(BUFFER_BYTES)
        while (input.read(buffer) >= 0) {
            // The request is ignored: this server never answers.
        }
        return true
    }

    private fun silentServerKubeconfig(port: Int) =
        """
        apiVersion: v1
        kind: Config
        clusters:
        - name: silent
          cluster:
            server: http://127.0.0.1:$port
        contexts:
        - name: silent
          context:
            cluster: silent
            user: silent
        current-context: silent
        users:
        - name: silent
          user:
            token: unused
        """.trimIndent()

    private companion object {
        const val ACCEPT_TIMEOUT_MS = 10_000
        const val BUFFER_BYTES = 4096

        /** Well under the client's 60s request timeout, so a lingering request fails the test. */
        const val CLOSE_WITHIN_SECONDS = 10L
    }
}
