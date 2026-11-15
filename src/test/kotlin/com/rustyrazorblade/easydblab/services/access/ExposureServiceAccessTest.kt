package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.Exposure
import io.fabric8.kubernetes.client.LocalPortForward
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The seam a command gets is the one its cluster's recorded exposure names. */
class ExposureServiceAccessTest {
    private companion object {
        const val WAIT_SECONDS = 5L

        // The slow open holds far longer than the second caller waits, so a lock held across it shows.
        const val HOLD_SECONDS = 30L
        const val SECOND_WAIT_SECONDS = 2L
    }

    private class Named(
        val name: String,
    ) : ServiceAccess {
        var closed = false

        override fun open(target: ServiceTarget): ServiceEndpoint = ServiceEndpoint.Plain(name, target.servicePort)

        override fun close() {
            closed = true
        }
    }

    private val built = mutableListOf<Named>()

    private fun accessFor(exposure: () -> Exposure) =
        ExposureServiceAccess(
            exposure,
            mapOf(
                Exposure.Socks to { Named("socks").also { built.add(it) } },
                Exposure.Direct to { Named("direct").also { built.add(it) } },
                Exposure.PortForward to { Named("port-forward").also { built.add(it) } },
            ),
        )

    @ParameterizedTest
    @EnumSource(Exposure::class)
    fun `the recorded exposure selects its implementation`(exposure: Exposure) {
        val expected = mapOf(Exposure.Socks to "socks", Exposure.Direct to "direct", Exposure.PortForward to "port-forward")

        assertThat(accessFor { exposure }.open(ClusterServices.LOKI).host).isEqualTo(expected.getValue(exposure))
    }

    @Test
    fun `an unchanged exposure keeps one implementation, closed with the access`() {
        val access = accessFor { Exposure.PortForward }

        access.open(ClusterServices.LOKI)
        access.open(ClusterServices.MIMIR)
        access.close()

        assertThat(built.single().closed).isTrue()
    }

    @Test
    fun `an exposure changed since the last open closes the old implementation and builds the new one`() {
        // A REPL can run init between two commands, recording another exposure.
        var recorded = Exposure.Socks
        val access = accessFor { recorded }

        access.open(ClusterServices.LOKI)
        recorded = Exposure.PortForward
        val endpoint = access.open(ClusterServices.LOKI)

        assertThat(endpoint.host).isEqualTo("port-forward")
        assertThat(built.map { it.name }).containsExactly("socks", "port-forward")
        assertThat(built.first().closed).isTrue()
        assertThat(built.last().closed).isFalse()
    }

    @Test
    fun `a slow open does not hold up another caller's open`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val slow =
            object : ServiceAccess {
                override fun open(target: ServiceTarget): ServiceEndpoint {
                    if (target == ClusterServices.LOKI) {
                        firstEntered.countDown()
                        releaseFirst.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    }
                    return ServiceEndpoint.Plain("slow", target.servicePort)
                }

                override fun close() = Unit
            }
        val access = ExposureServiceAccess({ Exposure.PortForward }, mapOf(Exposure.PortForward to { slow }))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<ServiceEndpoint> { access.open(ClusterServices.LOKI) }
            assertThat(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

            // The first open is still inside the implementation; a second one must not wait for it.
            val second = CompletableFuture.supplyAsync { access.open(ClusterServices.MIMIR) }

            assertThat(second.get(SECOND_WAIT_SECONDS, TimeUnit.SECONDS).port).isEqualTo(ClusterServices.MIMIR.servicePort)
            releaseFirst.countDown()
            assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).port).isEqualTo(ClusterServices.LOKI.servicePort)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    /**
     * Caller A selects the port-forward access and is held between selecting it and opening on it.
     * The exposure changes and caller B's open closes that access. A's open then lands on a closed
     * access, which refuses it, and A opens on the access the exposure now selects. Nothing is left
     * open on the access that was closed.
     */
    @Test
    fun `an open whose access was closed under it by an exposure change opens on the new access`() {
        val selectedForA = CountDownLatch(1)
        val resumeA = CountDownLatch(1)
        var recorded = Exposure.PortForward
        val forwardAccess = PortForwardServiceAccess({ _, port -> NeverFailingForward(port) }) { 21001 }
        val access =
            ExposureServiceAccess(
                exposure = { recorded },
                implementations =
                    mapOf(
                        Exposure.PortForward to { holdingFirstOpen(forwardAccess, selectedForA, resumeA) },
                        Exposure.Direct to { Named("direct").also { built.add(it) } },
                    ),
            )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val a = executor.submit<ServiceEndpoint> { access.open(ClusterServices.LOKI) }
            assertThat(selectedForA.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

            recorded = Exposure.Direct
            assertThat(access.open(ClusterServices.MIMIR).host).isEqualTo("direct")
            resumeA.countDown()

            assertThat(a.get(WAIT_SECONDS, TimeUnit.SECONDS).host).isEqualTo("direct")
            assertThat(opened).isEmpty()
        } finally {
            resumeA.countDown()
            executor.shutdownNow()
        }
    }

    private val opened = mutableListOf<Int>()

    /** A forward that stays usable, recording that it was opened until it is closed. */
    private inner class NeverFailingForward(
        private val port: Int,
    ) : LocalPortForward {
        init {
            opened.add(port)
        }

        override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()

        override fun getLocalPort(): Int = port

        override fun isAlive(): Boolean = true

        override fun errorOccurred(): Boolean = false

        override fun getClientThrowables(): Collection<Throwable> = emptyList()

        override fun getServerThrowables(): Collection<Throwable> = emptyList()

        override fun close() {
            opened.remove(port)
        }
    }

    /** [inner], whose first open signals [selected] and then waits for [resume] before going on. */
    private fun holdingFirstOpen(
        inner: ServiceAccess,
        selected: CountDownLatch,
        resume: CountDownLatch,
    ): ServiceAccess {
        val first =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        return object : ServiceAccess {
            override fun open(target: ServiceTarget): ServiceEndpoint {
                if (first.getAndSet(false)) {
                    selected.countDown()
                    resume.await(HOLD_SECONDS, TimeUnit.SECONDS)
                }
                return inner.open(target)
            }

            override fun close() = inner.close()
        }
    }

    @Test
    fun `closing an access that opened nothing reads no state`() {
        accessFor { error("no state") }.close()

        assertThat(built).isEmpty()
    }
}
