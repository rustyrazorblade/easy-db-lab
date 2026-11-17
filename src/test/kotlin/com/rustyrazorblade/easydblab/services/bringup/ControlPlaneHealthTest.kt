package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneHealth.Sample
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneHealth.Transition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class ControlPlaneHealthTest {
    private val start = Instant.parse("2026-11-16T00:00:00Z")

    private fun health() =
        ControlPlaneHealth(
            latencyLimitMs = 2000,
            rejectionLimit = 25,
            recoverySamples = 3,
            unhealthyTimeout = Duration.ofSeconds(900),
        )

    @ParameterizedTest(name = "latency {0}ms, {1} rejections: unhealthy={2}")
    @CsvSource("0, 0, false", "2000, 0, false", "2001, 0, true", "0, 25, false", "0, 26, true", "2001, 26, true")
    fun `one sample past either limit makes the control plane unhealthy`(
        latencyMs: Long,
        rejections: Long,
        unhealthy: Boolean,
    ) {
        val health = health()
        val sample = Sample.Reading(latencyMs, rejections)

        val transition = health.record(sample, start)

        assertThat(transition).isEqualTo(if (unhealthy) Transition.BecameUnhealthy(sample) else Transition.None)
        assertThat(health.healthy).isEqualTo(!unhealthy)
    }

    @Test
    fun `an API server that cannot be reached is unhealthy`() {
        val health = health()

        assertThat(health.record(Sample.Unreachable("connect timed out"), start))
            .isEqualTo(Transition.BecameUnhealthy(Sample.Unreachable("connect timed out")))
    }

    @Test
    fun `it recovers on the third healthy sample in a row, and a bad sample restarts the count`() {
        val health = health()
        val bad = Sample.Reading(5000, 0)
        val good = Sample.Reading(10, 0)
        health.record(bad, start)

        val transitions =
            listOf(good, good, bad, good, good, good).mapIndexed { i, sample ->
                health.record(sample, start.plusSeconds(5L * (i + 1)))
            }

        assertThat(transitions).containsExactly(
            Transition.None,
            Transition.None,
            Transition.None,
            Transition.None,
            Transition.None,
            Transition.BecameHealthy(unhealthyFor = Duration.ofSeconds(30)),
        )
        assertThat(health.healthy).isTrue()
    }

    @Test
    fun `it reports the change to unhealthy once, not on every bad sample`() {
        val health = health()
        val bad = Sample.Reading(5000, 0)

        assertThat(health.record(bad, start)).isEqualTo(Transition.BecameUnhealthy(bad))
        assertThat(health.record(bad, start.plusSeconds(5))).isEqualTo(Transition.None)
    }

    @Test
    fun `unhealthy for the whole timeout fails, naming the last sample`() {
        val health = health()
        health.record(Sample.Reading(5000, 0), start)

        assertThat(health.record(Sample.Reading(4000, 30), start.plusSeconds(899))).isEqualTo(Transition.None)
        assertThat(health.record(Sample.Reading(3000, 40), start.plusSeconds(900)))
            .isEqualTo(Transition.TimedOut(Sample.Reading(3000, 40)))
    }

    @Test
    fun `it agrees with a simple model over any sequence of samples`() {
        repeat(300) { seed ->
            val random = Random(seed)
            val health = health()
            var modelHealthy = true
            var goodRun = 0

            repeat(random.nextInt(1, 80)) { i ->
                val sample =
                    when (random.nextInt(4)) {
                        0 -> Sample.Unreachable("down")
                        else -> Sample.Reading(random.nextLong(0, 2600), random.nextLong(0, 32))
                    }
                val bad = sample is Sample.Unreachable || (sample is Sample.Reading && (sample.latencyMs > 2000 || sample.rejections > 25))
                if (bad) {
                    modelHealthy = false
                    goodRun = 0
                } else if (!modelHealthy) {
                    goodRun += 1
                    if (goodRun == 3) {
                        modelHealthy = true
                        goodRun = 0
                    }
                }

                health.record(sample, start.plusSeconds(i.toLong()))

                assertThat(health.healthy).describedAs("seed $seed, sample $i").isEqualTo(modelHealthy)
            }
        }
    }
}
