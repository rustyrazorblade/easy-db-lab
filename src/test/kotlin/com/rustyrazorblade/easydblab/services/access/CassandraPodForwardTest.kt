package com.rustyrazorblade.easydblab.services.access

import io.fabric8.kubernetes.client.LocalPortForward
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress

/** The pod forwarder stands in at the Kubernetes boundary, handing back forwards the test can kill. */
class CassandraPodForwardTest {
    private class FakeForward(
        private val port: Int,
        var failure: Throwable? = null,
    ) : LocalPortForward {
        var alive = true

        override fun getLocalAddress(): InetAddress = InetAddress.getByName("127.0.0.1")

        override fun getLocalPort(): Int = port

        override fun isAlive(): Boolean = alive

        override fun errorOccurred(): Boolean = failure != null

        override fun getClientThrowables(): Collection<Throwable> = listOfNotNull(failure)

        override fun getServerThrowables(): Collection<Throwable> = emptyList()

        override fun close() {
            alive = false
        }
    }

    private val opened = mutableListOf<Pair<PodTarget, Int>>()
    private val forwards = mutableListOf<FakeForward>()
    private var nextFailure: Throwable? = null
    private val ports = ArrayDeque(listOf(24001, 24002))
    private val pod = PodTarget("default", "cassandra-pods-0", 30942)

    private val forward =
        CassandraPodForward(
            pod = pod,
            forwarder = { target, localPort ->
                opened += target to localPort
                FakeForward(localPort, nextFailure).also { forwards += it }
            },
            localPorts = { ports.removeFirst() },
        )

    @Test
    fun `the pod's native port is forwarded on loopback`() {
        assertThat(forward.endpoint()).isEqualTo(InetSocketAddress("127.0.0.1", 24001))
        assertThat(opened).containsExactly(pod to 24001)
    }

    @Test
    fun `a live forward is reused`() {
        forward.endpoint()
        forward.endpoint()

        assertThat(opened).hasSize(1)
    }

    @Test
    fun `a dead forward is restarted on the same local port, so the driver reconnects to the address it knows`() {
        forward.endpoint()
        forwards.single().alive = false

        val again = forward.endpoint()

        assertThat(again).isEqualTo(InetSocketAddress("127.0.0.1", 24001))
        assertThat(opened.map { it.second }).containsExactly(24001, 24001)
    }

    @Test
    fun `a forward that stays alive but reported an error is rebuilt on the same local port`() {
        forward.endpoint()
        // Fabric8 keeps isAlive true after the pod is replaced; only the error shows it.
        forwards.single().failure = IllegalStateException("pod \"cassandra-pods-0\" not found")

        assertThat(forward.endpoint()).isEqualTo(InetSocketAddress("127.0.0.1", 24001))
        assertThat(opened.map { it.second }).containsExactly(24001, 24001)
        assertThat(forwards.first().alive).isFalse()
    }

    @Test
    fun `a forward that fails names the namespace, the pod, the port and the error`() {
        nextFailure = IllegalStateException("pods \"cassandra-pods-0\" not found")

        assertThatThrownBy { forward.endpoint() }
            .hasMessageContaining("default/cassandra-pods-0")
            .hasMessageContaining("30942")
            .hasMessageContaining("not found")
        assertThat(forwards.single().alive).isFalse()
    }

    @Test
    fun `a fixed port another process holds fails naming the port and the process, not retrying elsewhere`() {
        val tried = mutableListOf<Int>()
        val taken =
            CassandraPodForward(
                pod = pod,
                forwarder = { _, localPort ->
                    tried += localPort
                    throw IllegalStateException("Unable to port forward", BindException("Address already in use"))
                },
                localPorts = { ports.removeFirst() },
            )

        assertThatThrownBy { taken.endpoint() }
            .hasMessageContaining("127.0.0.1:24001")
            .hasMessageContaining("another process")
            .hasRootCauseInstanceOf(BindException::class.java)
        assertThat(tried).containsExactly(24001)
    }

    @Test
    fun `close closes the forward`() {
        forward.endpoint()

        forward.close()

        assertThat(forwards.single().alive).isFalse()
    }
}
