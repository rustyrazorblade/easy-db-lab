package com.rustyrazorblade.easydblab.services.access

import io.fabric8.kubernetes.client.LocalPortForward
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The forwarder stands in at the Kubernetes boundary: it records each forward it is asked for and
 * hands back a [FakeForward] the test can kill or fail.
 */
class PortForwardServiceAccessTest {
    private class FakeForward(
        private val port: Int,
        var alive: Boolean = true,
        var failure: Throwable? = null,
    ) : LocalPortForward {
        var closed = false

        override fun getLocalAddress(): InetAddress = InetAddress.getByName("127.0.0.1")

        override fun getLocalPort(): Int = port

        override fun isAlive(): Boolean = alive && !closed

        override fun errorOccurred(): Boolean = failure != null

        override fun getClientThrowables(): Collection<Throwable> = listOfNotNull(failure)

        override fun getServerThrowables(): Collection<Throwable> = emptyList()

        override fun close() {
            closed = true
        }
    }

    private val opened = mutableListOf<Pair<ServiceTarget, Int>>()
    private val forwards = mutableListOf<FakeForward>()
    private var nextFailure: Throwable? = null
    private val ports = ArrayDeque(listOf(21001, 21002, 21003))

    private val forwarder =
        ServiceForwarder { target, localPort ->
            opened.add(target to localPort)
            FakeForward(localPort, failure = nextFailure).also { forwards.add(it) }
        }

    private val access = PortForwardServiceAccess(forwarder) { ports.removeFirst() }

    @Test
    fun `a Service is reached on loopback at the forward's local port`() {
        assertThat(access.open(ClusterServices.LOKI)).isEqualTo(ServiceEndpoint.Plain("127.0.0.1", 21001))
        assertThat(opened.single()).isEqualTo(ClusterServices.LOKI to 21001)
    }

    @Test
    fun `a command reuses one forward per Service`() {
        access.open(ClusterServices.LOKI)
        access.open(ClusterServices.LOKI)
        access.open(ClusterServices.MIMIR)

        assertThat(opened.map { it.first }).containsExactly(ClusterServices.LOKI, ClusterServices.MIMIR)
    }

    @Test
    fun `a forward that died is reopened`() {
        access.open(ClusterServices.LOKI)
        forwards.single().alive = false

        assertThat(access.open(ClusterServices.LOKI)).isEqualTo(ServiceEndpoint.Plain("127.0.0.1", 21002))
        assertThat(forwards.first().closed).isTrue()
    }

    @Test
    fun `a forward that stays alive but reported an error since it opened is reopened, re-resolving its pod`() {
        access.open(ClusterServices.LOKI)
        // Fabric8 keeps isAlive true after the Service's pod is replaced; only the error shows it.
        forwards.single().failure = IllegalStateException("pod \"loki-0\" not found")

        assertThat(access.open(ClusterServices.LOKI)).isEqualTo(ServiceEndpoint.Plain("127.0.0.1", 21002))
        assertThat(opened).hasSize(2)
        assertThat(forwards.first().closed).isTrue()
    }

    @Test
    fun `a forward that reports an error fails naming the namespace, Service, port and error`() {
        nextFailure = IllegalStateException("pods \"loki-0\" is forbidden")

        assertThatThrownBy { access.open(ClusterServices.LOKI) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("default/loki")
            .hasMessageContaining("3100")
            .hasMessageContaining("forbidden")
        assertThat(forwards.single().closed).isTrue()
    }

    @Test
    fun `a forward that cannot open fails naming the Service and the Kubernetes error`() {
        val failing =
            PortForwardServiceAccess({ _, _ -> throw IllegalStateException("services \"loki\" not found") }) { 21001 }

        assertThatThrownBy { failing.open(ClusterServices.LOKI) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("default/loki")
            .hasMessageContaining("3100")
            .hasMessageContaining("not found")
    }

    @Test
    fun `a port another process bound after it was picked is retried once on a fresh port`() {
        val tried = mutableListOf<Int>()
        val racing =
            PortForwardServiceAccess({ _, localPort ->
                tried += localPort
                // Fabric8 wraps the bind's IOException in an IllegalStateException.
                if (tried.size == 1) throw IllegalStateException("Unable to port forward", BindException("Address already in use"))
                FakeForward(localPort)
            }) { ports.removeFirst() }

        assertThat(racing.open(ClusterServices.LOKI)).isEqualTo(ServiceEndpoint.Plain("127.0.0.1", 21002))
        assertThat(tried).containsExactly(21001, 21002)
    }

    @Test
    fun `a bind that fails again on the fresh port fails, and other errors are not retried`() {
        val tried = mutableListOf<Int>()
        val alwaysTaken =
            PortForwardServiceAccess({ _, localPort ->
                tried += localPort
                throw IllegalStateException("Unable to port forward", BindException("Address already in use"))
            }) { ports.removeFirst() }

        assertThatThrownBy { alwaysTaken.open(ClusterServices.LOKI) }
            .hasMessageContaining("default/loki")
            .hasRootCauseInstanceOf(BindException::class.java)
        assertThat(tried).containsExactly(21001, 21002)

        // A failure that is not a bind takes no fresh port: the one port left is the one it used.
        val forbidden = PortForwardServiceAccess({ _, _ -> throw IllegalStateException("forbidden") }) { ports.removeFirst() }
        assertThatThrownBy { forbidden.open(ClusterServices.LOKI) }.hasMessageContaining("forbidden")
        assertThat(ports).isEmpty()
    }

    @Test
    fun `a target with no Service named fails saying how to declare one, opening nothing`() {
        val undeclared = ClusterServices.LOKI.copy(service = "", servicePort = 31090, nodePort = 31090)

        assertThatThrownBy { access.open(undeclared) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("31090")
            .hasMessageContaining("service:")
        assertThat(opened).isEmpty()
    }

    @Test
    fun `closing the access closes every forward it opened`() {
        access.open(ClusterServices.LOKI)
        access.open(ClusterServices.GRAFANA)

        access.close()

        assertThat(forwards).allMatch { it.closed }
    }

    @Test
    fun `a closed access refuses to open, so no forward outlives it`() {
        access.close()

        assertThatThrownBy { access.open(ClusterServices.LOKI) }.isInstanceOf(ServiceAccessClosedException::class.java)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `a close that comes while an open is in flight closes the forward that open made`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow =
            PortForwardServiceAccess({ _, localPort ->
                entered.countDown()
                release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                FakeForward(localPort).also { forwards.add(it) }
            }) { ports.removeFirst() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val opening = executor.submit<ServiceEndpoint> { slow.open(ClusterServices.LOKI) }
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            val closing = executor.submit { slow.close() }

            release.countDown()
            opening.get(WAIT_SECONDS, TimeUnit.SECONDS)
            closing.get(WAIT_SECONDS, TimeUnit.SECONDS)

            assertThat(forwards.single().closed).isTrue()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val HOLD_SECONDS = 30L
    }
}
