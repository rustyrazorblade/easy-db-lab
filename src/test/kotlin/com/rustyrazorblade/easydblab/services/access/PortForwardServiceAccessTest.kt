package com.rustyrazorblade.easydblab.services.access

import io.fabric8.kubernetes.client.LocalPortForward
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * The forwarder stands in at the Kubernetes boundary: it records each forward it is asked for and
 * hands back a [FakeForward] the test can kill or fail.
 */
class PortForwardServiceAccessTest {
    private class FakeForward(
        private val port: Int,
        var alive: Boolean = true,
        val failure: Throwable? = null,
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
    fun `closing the access closes every forward it opened`() {
        access.open(ClusterServices.LOKI)
        access.open(ClusterServices.GRAFANA)

        access.close()

        assertThat(forwards).allMatch { it.closed }
    }
}
