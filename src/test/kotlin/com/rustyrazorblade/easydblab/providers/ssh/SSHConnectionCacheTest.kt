package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.MockSSHClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
}
