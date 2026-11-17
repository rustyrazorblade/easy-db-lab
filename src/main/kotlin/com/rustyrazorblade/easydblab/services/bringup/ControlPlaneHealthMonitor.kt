package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

/**
 * Acts on the control plane's health while nodes join (design D8). Each [tick] takes one sample
 * and feeds it to [health]: on the change to unhealthy it closes the [latch] (no join permit, no
 * replacement) and reports it, on recovery it opens the latch and reports how long it lasted, and
 * past the unhealthy deadline it calls [onFailed] with a reason naming the last sample, because
 * the control node has failed. A sampler that throws counts as an unreachable API server.
 *
 * `NodeBringUp` runs [tick] on a [Ticker] from the K3s server's start until the last pipeline ends.
 */
class ControlPlaneHealthMonitor(
    private val sampler: HealthSampler,
    private val health: ControlPlaneHealth,
    private val latch: HealthLatch,
    private val progress: BringUpProgress,
    private val eventBus: EventBus,
    private val clock: Clock,
    private val onFailed: (reason: String) -> Unit,
) {
    /** Takes one sample and acts on what it changed. */
    fun tick() {
        when (val transition = health.record(sample(), clock.instant())) {
            ControlPlaneHealth.Transition.None -> Unit
            is ControlPlaneHealth.Transition.BecameUnhealthy -> {
                latch.markUnhealthy()
                progress.controlPlane(Event.Provision.ControlPlaneState.UNHEALTHY)
                eventBus.emit(unhealthyEvent(transition.sample))
            }
            is ControlPlaneHealth.Transition.BecameHealthy -> {
                latch.markHealthy()
                progress.controlPlane(Event.Provision.ControlPlaneState.HEALTHY)
                eventBus.emit(Event.Provision.ControlPlaneHealthy(transition.unhealthyFor.toSeconds()))
            }
            is ControlPlaneHealth.Transition.TimedOut -> onFailed(timedOutReason(transition.sample))
        }
    }

    private fun sample(): ControlPlaneHealth.Sample =
        try {
            sampler.sample()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            log.debug(e) { "Health sample failed" }
            ControlPlaneHealth.Sample.Unreachable(e.message ?: e::class.java.simpleName)
        }

    private fun unhealthyEvent(sample: ControlPlaneHealth.Sample): Event.Provision.ControlPlaneUnhealthy =
        when (sample) {
            is ControlPlaneHealth.Sample.Reading ->
                Event.Provision.ControlPlaneUnhealthy(sample.latencyMs, sample.rejections, health.latencyLimitMs, health.rejectionLimit)
            is ControlPlaneHealth.Sample.Unreachable ->
                Event.Provision.ControlPlaneUnhealthy(null, 0, health.latencyLimitMs, health.rejectionLimit, sample.reason)
        }

    private fun timedOutReason(sample: ControlPlaneHealth.Sample): String {
        val last =
            when (sample) {
                is ControlPlaneHealth.Sample.Reading ->
                    "get nodes took ${sample.latencyMs}ms (limit ${health.latencyLimitMs}ms), " +
                        "${sample.rejections} rejected requests (limit ${health.rejectionLimit})"
                is ControlPlaneHealth.Sample.Unreachable -> "the API server could not be reached (${sample.reason})"
            }
        return "the control plane stayed unhealthy past its deadline; last sample: $last"
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
