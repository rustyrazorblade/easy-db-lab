package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque

/**
 * What [ControlPlaneHealthMonitor] does with each transition (design D8): the latch and the
 * progress line follow the health, each change is reported once, a sampler that throws counts as
 * unreachable, and the deadline calls the failure handler with the last sample.
 */
class ControlPlaneHealthMonitorTest {
    private val events = mutableListOf<Event>()
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }
    private val latch = HealthLatch()
    private val progress = BringUpProgress(total = 1, joinLimit = 1)
    private val failures = mutableListOf<String>()
    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }

    private fun monitor(samples: List<() -> ControlPlaneHealth.Sample>): ControlPlaneHealthMonitor {
        val queue = ArrayDeque(samples)
        return ControlPlaneHealthMonitor(
            { queue.removeFirst()() },
            ControlPlaneHealth(unhealthyTimeout = Duration.ofSeconds(DEADLINE_SECONDS)),
            latch,
            progress,
            eventBus,
            clock,
        ) { failures += it }
    }

    private fun reading(latencyMs: Long) = { ControlPlaneHealth.Sample.Reading(latencyMs, 0) }

    private fun tick(
        monitor: ControlPlaneHealthMonitor,
        times: Int,
    ) = repeat(times) {
        monitor.tick()
        now = now.plusSeconds(STEP_SECONDS)
    }

    @Test
    fun `an unhealthy sample closes the latch and reports once, and three healthy ones reopen it and report once`() {
        val m = monitor(listOf(reading(SLOW), reading(SLOW), reading(1), reading(1), reading(1)))

        tick(m, 2)
        assertThat(latch.healthy).isFalse()
        assertThat(progress.snapshot().controlPlane).isEqualTo(Event.Provision.ControlPlaneState.UNHEALTHY)
        val unhealthy = events.filterIsInstance<Event.Provision.ControlPlaneUnhealthy>().single()
        assertThat(unhealthy.latencyMs).isEqualTo(SLOW)

        tick(m, 3)
        assertThat(latch.healthy).isTrue()
        assertThat(progress.snapshot().controlPlane).isEqualTo(Event.Provision.ControlPlaneState.HEALTHY)
        assertThat(events.filterIsInstance<Event.Provision.ControlPlaneHealthy>().single().unhealthySeconds)
            .isEqualTo(STEP_SECONDS * 4)
        assertThat(failures).isEmpty()
    }

    @Test
    fun `an interrupt while sampling stops the tick and is not reported as an unreachable API server`() {
        val m = monitor(listOf({ throw InterruptedException("stop") }))

        try {
            assertThatThrownBy { m.tick() }.isInstanceOf(InterruptedException::class.java)
        } finally {
            Thread.interrupted()
        }

        assertThat(events.filterIsInstance<Event.Provision.ControlPlaneUnhealthy>()).isEmpty()
    }

    @Test
    fun `a sampler that throws counts as an unreachable API server`() {
        val m = monitor(listOf({ throw IllegalStateException("connection refused") }))

        tick(m, 1)

        assertThat(latch.healthy).isFalse()
        val unhealthy = events.filterIsInstance<Event.Provision.ControlPlaneUnhealthy>().single()
        assertThat(unhealthy.latencyMs).isNull()
        assertThat(unhealthy.toDisplayString()).contains("API server unreachable (connection refused)")
    }

    /**
     * Stopping the monitor interrupts its thread, which can cut a sample short: the client fails
     * with its interrupt flag set. That says nothing about the control plane, so nothing changes.
     */
    @Test
    fun `a sample cut short by the monitor's stop changes nothing`() {
        val m =
            monitor(
                listOf({
                    Thread.currentThread().interrupt()
                    error("An error has occurred.")
                }),
            )

        try {
            m.tick()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }

        assertThat(latch.healthy).isTrue()
        assertThat(events.filterIsInstance<Event.Provision.ControlPlaneUnhealthy>()).isEmpty()
        assertThat(progress.snapshot().controlPlane).isNotEqualTo(Event.Provision.ControlPlaneState.UNHEALTHY)
    }

    /**
     * The client may turn the stop's interrupt into an I/O error and clear the flag. The monitor is
     * marked stopped before its ticker is closed, so that sample is dropped too.
     */
    @Test
    fun `a sample that fails after the monitor was stopped, with no interrupt left, changes nothing`() {
        lateinit var m: ControlPlaneHealthMonitor
        m =
            monitor(
                listOf({
                    m.stop()
                    throw InterruptedIOException("interrupted")
                }),
            )

        m.tick()

        assertThat(latch.healthy).isTrue()
        assertThat(events.filterIsInstance<Event.Provision.ControlPlaneUnhealthy>()).isEmpty()
    }

    @Test
    fun `unhealthy past the deadline calls the failure handler with the last sample`() {
        val samples = (0..DEADLINE_SECONDS / STEP_SECONDS).map { reading(SLOW + it) }
        val m = monitor(samples)

        tick(m, samples.size)

        assertThat(failures).hasSize(1)
        assertThat(failures.single()).contains("${SLOW + DEADLINE_SECONDS / STEP_SECONDS}ms")
    }

    private companion object {
        const val SLOW = 5_000L
        const val STEP_SECONDS = 5L
        const val DEADLINE_SECONDS = 30L
    }
}
