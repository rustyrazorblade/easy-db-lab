package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
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
