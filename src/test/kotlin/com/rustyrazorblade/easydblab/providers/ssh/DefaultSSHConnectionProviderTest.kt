package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [DefaultSSHConnectionProvider] with a real MINA client against a loopback listener that accepts
 * TCP and then closes without a word of SSH, as a broken SSM forward does. A failed connection must
 * tell the route, so the next attempt is not dialed down the same broken path.
 */
internal class DefaultSSHConnectionProviderTest {
    @TempDir
    lateinit var dir: File

    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val acceptor =
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                runCatching { listener.accept().close() }
            }
        }

    private val host = Host(public = "54.1.2.3", private = "10.0.0.7", alias = "db0", availabilityZone = "a", instanceId = "i-0abc")

    /** A route that always points at [port] and records what it was asked. */
    private class RecordingRoute(
        private val port: Int,
        override val authTimeout: Duration? = null,
    ) : SshRoute {
        val endpointRequests = mutableListOf<Host>()
        val invalidated = mutableListOf<Host>()

        override fun endpoint(host: Host): SshEndpoint = SshEndpoint("127.0.0.1", port).also { endpointRequests.add(host) }

        override fun proxyCommand(host: Host): String? = null

        override val tunnelVerifyAttempts: Int = 1

        override fun invalidate(host: Host) {
            invalidated.add(host)
        }
    }

    private val route = RecordingRoute(listener.localPort)
    private lateinit var created: DefaultSSHConnectionProvider

    private fun provider(route: SshRoute = this.route): DefaultSSHConnectionProvider {
        val key = File(dir, "id_rsa")
        val keygen =
            ProcessBuilder("ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-m", "PEM", "-N", "", "-f", key.absolutePath)
                .redirectErrorStream(true)
                .start()
        check(keygen.waitFor(KEYGEN_LIMIT_SECONDS, TimeUnit.SECONDS) && keygen.exitValue() == 0) { "ssh-keygen failed" }
        return DefaultSSHConnectionProvider(DefaultSSHConfiguration(keyPath = key.absolutePath, connectionTimeoutSeconds = 5), route)
            .also { created = it }
    }

    @AfterEach
    fun close() {
        listener.close()
        acceptor.join(TimeUnit.SECONDS.toMillis(1))
        if (::created.isInitialized) created.stop()
    }

    @Test
    fun `a connection that fails before authentication invalidates the route so the next attempt asks for a new endpoint`() {
        val provider = provider()

        assertThatThrownBy { provider.getConnection(host) }.isInstanceOf(IOException::class.java)
        assertThat(route.invalidated).containsExactly(host)

        assertThatThrownBy { provider.getConnection(host) }.isInstanceOf(IOException::class.java)
        assertThat(route.endpointRequests).hasSize(2)
        assertThat(route.invalidated).hasSize(2)
    }

    /**
     * A forward that accepts TCP and then sends nothing holds the connection until the auth
     * timeout. The provider must use the route's bound, not MINA's 120s default.
     */
    @Test
    fun `a connection that never authenticates fails at the route's auth timeout`() {
        val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val held = mutableListOf<java.net.Socket>()
        val holder = thread(isDaemon = true) { while (!silent.isClosed) runCatching { held.add(silent.accept()) } }
        try {
            val provider = provider(RecordingRoute(silent.localPort, authTimeout = Duration.ofSeconds(1)))
            val startedAt = System.nanoTime()

            assertThatThrownBy { provider.getConnection(host) }.isInstanceOf(IOException::class.java)

            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(AUTH_FAIL_LIMIT_SECONDS))
        } finally {
            silent.close()
            held.forEach { it.close() }
            holder.join(TimeUnit.SECONDS.toMillis(1))
        }
    }

    @Test
    fun `discarding a host invalidates its route`() {
        val provider = provider()

        provider.discard(host)

        assertThat(route.invalidated).containsExactly(host)
    }

    private companion object {
        const val KEYGEN_LIMIT_SECONDS = 30L

        // Far below MINA's 120s default, far above the 1s bound the route sets.
        const val AUTH_FAIL_LIMIT_SECONDS = 10L
    }
}
