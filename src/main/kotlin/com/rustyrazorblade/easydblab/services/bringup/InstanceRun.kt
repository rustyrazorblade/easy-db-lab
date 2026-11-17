package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import java.util.concurrent.CompletableFuture

/**
 * One instance's way through the steps every node has, control included (design D2): running,
 * status checks, SSH and the image check, plus running any further step under the step runner
 * and waiting on a latch. It tracks the instance's [host] as the provider fills in its addresses.
 *
 * A step that does not succeed calls [stop] with the step, the reason, whether a new instance
 * could fix it, and the instance as known then. [stop] never returns, so each pipeline reads as a
 * straight list of steps. A wait or a step stops at once when the instance ends, as replaceable.
 */
internal class InstanceRun(
    private val run: BringUpRun,
    private val serverType: ServerType,
    launched: ClusterHost,
    private val onEnter: (PipelineStep) -> Unit,
    private val stop: (step: PipelineStep, reason: String, replaceable: Boolean, host: ClusterHost) -> Nothing,
) : AutoCloseable {
    private val watch = run.ports.tracker.watch(launched.instanceId)

    /** The instance as it is known now. */
    var host: ClusterHost = launched
        private set

    /**
     * Runs [action] as [step] under [policy], and returns its value. A step that fails after its
     * attempts stops with the reason [explain] makes of the last attempt's.
     */
    fun <T> step(
        step: PipelineStep,
        policy: StepPolicy,
        explain: (reason: String) -> String = { it },
        action: (attempt: Int) -> T,
    ): T {
        onEnter(step)
        return when (val outcome = run.runner.run(host.alias, step, policy, watch.ended, action)) {
            is StepOutcome.Succeeded -> outcome.value
            is StepOutcome.Failed -> stop(step, explain(outcome.reason), outcome.replaceable, host)
            is StepOutcome.InstanceEnded -> stop(step, ended(outcome.state), true, host)
        }
    }

    /** Waits, as [step], for [latch], or stops when the instance ends first. */
    fun <T> await(
        step: PipelineStep,
        latch: CompletableFuture<T>,
    ): T {
        onEnter(step)
        CompletableFuture.anyOf(latch, watch.ended).get()
        watch.ended.getNow(null)?.let { stop(step, ended(it), true, host) }
        return latch.get()
    }

    /** Running with its addresses, the provider's status checks, and an answer over SSH. */
    fun reachable() {
        val addresses = step(PipelineStep.RUNNING, run.timing.runningPolicy) { watch.running.get() }
        val placed = host.copy(publicIp = addresses.publicIp, privateIp = addresses.privateIp ?: host.privateIp)
        if (placed != host) {
            host = placed
            run.ports.state.mergeHosts(serverType, listOf(placed))
        }
        step(PipelineStep.STATUS_CHECKS, run.timing.statusPolicy) { watch.statusOk.get() }
        step(PipelineStep.SSH, run.timing.sshPolicy) { awaitSsh() }
    }

    /**
     * Fails for good when the image lacks what the cluster needs: a new instance has the same
     * image. A check that could not run (its SSH command died) says nothing about the image, so it
     * is retried and replaced like any other step.
     */
    fun checkImage() {
        val defect =
            step(PipelineStep.IMAGE_CHECK, run.timing.imageCheckPolicy) {
                run.ssh.withSession { run.ports.nodeSteps.checkImage(host) }
            } ?: return
        run.eventBus.emit(defect.report)
        stop(PipelineStep.IMAGE_CHECK, defect.reason, false, host)
    }

    /** Stops watching the instance. */
    override fun close() = run.ports.tracker.unwatch(watch.instanceId)

    private fun awaitSsh() {
        val timing = run.timing
        val answered =
            pollUntil("${host.alias}-ssh", timing.sshInterval, timing.sshTimeout, done = { it }) {
                run.ssh.withSession { run.ports.nodeSteps.sshAnswers(host) }
            }
        check(answered) { "${host.alias} did not answer over SSH within ${timing.sshTimeout.toSeconds()}s" }
    }

    private fun ended(state: String) = "the instance ended ($state)"
}
