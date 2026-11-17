package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.MockSSHClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class SSHConnectionCacheTest {
    private val host = Host(public = "54.0.0.1", private = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")

    /** The parallel teardown steps ask for the control node's connection at the same time. */
    @Test
    fun `concurrent requests for one host open one connection`() {
        val opened = AtomicInteger()
        val cache =
            SSHConnectionCache {
                opened.incrementAndGet()
                // Hold the connect open long enough for every caller to be racing it.
                Thread.sleep(50)
                MockSSHClient()
            }
        val callers = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(callers)

        val clients =
            pool.use { executor ->
                val futures = (1..callers).map { executor.submit<ISSHClient> { start.await().let { cache.get(host) } } }
                start.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            }

        assertThat(opened.get()).isEqualTo(1)
        assertThat(clients.toSet()).hasSize(1)
    }

    /**
     * Connecting to one host must never wait on another. Each connect here blocks until every
     * host's connect has started, so the test only finishes if all of them run at once; a cache
     * that connects inside a shared map lock deadlocks until the latch times out.
     */
    @Test
    fun `connections to different hosts are opened concurrently`() {
        val hosts = (0 until 12).map { Host(public = "54.0.0.$it", private = "10.0.0.$it", alias = "db$it", availabilityZone = "a") }
        val allConnecting = CountDownLatch(hosts.size)
        val cache =
            SSHConnectionCache {
                allConnecting.countDown()
                check(allConnecting.await(10, TimeUnit.SECONDS)) { "connects to different hosts did not overlap" }
                MockSSHClient()
            }
        val pool = Executors.newFixedThreadPool(hosts.size)

        val clients =
            pool.use { executor ->
                hosts.map { h -> executor.submit<ISSHClient> { cache.get(h) } }.map { it.get(30, TimeUnit.SECONDS) }
            }

        assertThat(clients.toSet()).hasSize(hosts.size)
    }

    @Test
    fun `a dropped connection is closed and the next request opens a new one`() {
        val first = mock<ISSHClient>().also { whenever(it.isSessionOpen()).thenReturn(true) }
        val second = MockSSHClient()
        val connections = ArrayDeque(listOf(first, second))
        val cache = SSHConnectionCache { connections.removeFirst() }
        cache.get(host)

        cache.drop(host)

        verify(first).close()
        assertThat(cache.get(host)).isSameAs(second)
    }

    @Test
    fun `a closed session is closed and replaced by a new connection`() {
        val stale = mock<ISSHClient>().also { whenever(it.isSessionOpen()).thenReturn(false) }
        val fresh = MockSSHClient()
        val connections = ArrayDeque(listOf(stale, fresh))
        val cache = SSHConnectionCache { connections.removeFirst() }

        assertThat(cache.get(host)).isSameAs(stale)
        assertThat(cache.get(host)).isSameAs(fresh)
        verify(stale).close()
        assertThat(cache.get(host)).isSameAs(fresh)
    }

    /** One connection that will not close must not keep the others open. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("closeFailures")
    fun `closing every connection closes the rest when one fails to close`(failure: Exception) {
        val hosts = listOf(host, host.copy(alias = "db0", private = "10.0.0.2"), host.copy(alias = "db1", private = "10.0.0.3"))
        val clients = hosts.map { mock<ISSHClient>().also { client -> whenever(client.isSessionOpen()).thenReturn(true) } }
        doAnswer { throw failure }.whenever(clients[0]).close()
        val byHost = hosts.zip(clients).toMap()
        val cache = SSHConnectionCache { byHost.getValue(it) }
        hosts.forEach { cache.get(it) }

        cache.closeAll()

        clients.forEach { verify(it).close() }
        assertThat(cache.size()).isZero()
    }

    @Test
    fun `an error in the JVM while closing a connection is not swallowed`() {
        val client = mock<ISSHClient>().also { whenever(it.isSessionOpen()).thenReturn(true) }
        whenever(client.close()).thenThrow(OutOfMemoryError("heap"))
        val cache = SSHConnectionCache { client }
        cache.get(host)

        assertThatThrownBy { cache.closeAll() }.isInstanceOf(OutOfMemoryError::class.java)
    }

    companion object {
        @JvmStatic
        fun closeFailures(): Stream<Exception> =
            Stream.of(IOException("Connection reset"), IllegalStateException("session already closed"), RuntimeException("boom"))
    }
}
