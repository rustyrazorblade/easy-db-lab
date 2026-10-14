package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.services.aws.DefaultSshIngressService
import com.rustyrazorblade.easydblab.services.aws.IngressResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Tests [DefaultExternalIpService] against a local socket server, so a public IP service that
 * accepts a connection and then sends nothing is reproduced exactly.
 */
class ExternalIpServiceIntegrationTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val accepted = CopyOnWriteArrayList<Socket>()

    @AfterEach
    fun closeServer() {
        accepted.forEach { it.close() }
        server.close()
    }

    @Test
    fun `a server that accepts and then sends nothing fails detection after the read timeout`() {
        thread(isDaemon = true) { accepted.add(server.accept()) }
        val service = DefaultExternalIpService(url = serverUrl(), connectTimeoutMs = 1_000, readTimeoutMs = 300)

        val started = System.nanoTime()
        assertThatThrownBy { service.getExternalIpAddress() }.isInstanceOf(SocketTimeoutException::class.java)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(elapsedMs).isBetween(250L, 5_000L)
    }

    @Test
    fun `ingress allow with no value reports a detection failure when the server stalls`(
        @TempDir workspace: File,
    ) {
        thread(isDaemon = true) { accepted.add(server.accept()) }
        val stateManager = ClusterStateManager(File(workspace, "state.json"))
        stateManager.save(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                infrastructure = InfrastructureState(vpcId = "vpc-1", region = "us-west-2", securityGroupId = "sg-1"),
            ),
        )
        val vpcService = mock<VpcService>()
        val externalIp = DefaultExternalIpService(url = serverUrl(), connectTimeoutMs = 1_000, readTimeoutMs = 300)

        val result = DefaultSshIngressService(stateManager, externalIp, vpcService).allow(emptyList())

        assertThat(result).isInstanceOf(IngressResult.DetectionFailed::class.java)
        verifyNoInteractions(vpcService)
    }

    @Test
    fun `the address the server answers with is returned`() {
        thread(isDaemon = true) {
            server.accept().use { socket ->
                // Read the request head up to its blank line before answering.
                socket
                    .getInputStream()
                    .bufferedReader()
                    .lineSequence()
                    .takeWhile { it.isNotEmpty() }
                    .count()
                val body = "203.0.113.10"
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                )
            }
        }
        val service = DefaultExternalIpService(url = serverUrl(), connectTimeoutMs = 1_000, readTimeoutMs = 2_000)

        assertThat(service.getExternalIpAddress()).isEqualTo("203.0.113.10")
    }

    private fun serverUrl() = URI("http://127.0.0.1:${server.localPort}/").toURL()
}
