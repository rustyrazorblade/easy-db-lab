package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ServerType
import java.time.Duration

/**
 * What one bring-up is asked to do: how many database and application nodes it expects (the
 * cluster replacement limit and the progress total come from it), the network plugin, whether to
 * stop after SSH (`--no-setup`), which database and application nodes to run (`--hosts`; the
 * control node always runs), how many nodes may join at once, how many SSH sessions may be open,
 * and its [timing].
 */
data class BringUpSettings(
    val nodeCount: Int,
    val cni: CniMode,
    val noSetup: Boolean = false,
    val hostFilter: String = "",
    val joinLimit: Int = Constants.Scale.JOIN_CONCURRENCY,
    val sshSessions: Int = Constants.Scale.HOST_CONCURRENCY,
    val timing: BringUpTiming = BringUpTiming(),
) {
    private val named: Set<String> =
        hostFilter
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Whether [alias] is one of the nodes `--hosts` names, or `--hosts` names none. */
    fun includes(alias: String): Boolean = named.isEmpty() || alias in named

    /** The nodes this bring-up runs pipelines for, the progress line's total: the ones `--hosts` names, or all. */
    val runCount: Int
        get() = if (named.isEmpty()) nodeCount else minOf(named.count { ServerType.Control.ordinalOf(it) == null }, nodeCount)
}

/**
 * Every wait and policy of a bring-up (design D5). The defaults are the production values from
 * `Constants.Scale`; the tests pass short ones.
 */
data class BringUpTiming(
    val running: Duration = Duration.ofSeconds(Constants.Scale.INSTANCE_RUNNING_TIMEOUT_SECONDS),
    val statusChecks: Duration = Duration.ofSeconds(Constants.Scale.INSTANCE_STATUS_TIMEOUT_SECONDS),
    val sshTimeout: Duration = Duration.ofSeconds(Constants.Scale.SSH_READY_TIMEOUT_SECONDS),
    val sshInterval: Duration = Duration.ofSeconds(Constants.Scale.SSH_PROBE_INTERVAL_SECONDS),
    val retryInterval: Duration = Duration.ofSeconds(Constants.Scale.STEP_RETRY_INTERVAL_SECONDS),
    val attemptStopGrace: Duration = Duration.ofSeconds(Constants.Scale.ATTEMPT_STOP_GRACE_SECONDS),
    val imageCheck: Duration = Duration.ofSeconds(Constants.Scale.IMAGE_CHECK_TIMEOUT_SECONDS),
    val setup: StepPolicy = StepPolicy.SETUP,
    val registryTls: StepPolicy = StepPolicy.REGISTRY_TLS,
    val join: StepPolicy = StepPolicy.JOIN,
    val ready: StepPolicy = StepPolicy.READY,
    val controlStep: Duration = Duration.ofSeconds(Constants.Scale.CONTROL_STEP_TIMEOUT_SECONDS),
    val gateTimeout: Duration = Duration.ofSeconds(Constants.Scale.GATE_TIMEOUT_SECONDS),
    val gateInterval: Duration = Duration.ofSeconds(Constants.Scale.GATE_POLL_INTERVAL_SECONDS),
    val healthInterval: Duration = Duration.ofSeconds(Constants.Scale.HEALTH_SAMPLE_INTERVAL_SECONDS),
    val unhealthyTimeout: Duration = Duration.ofSeconds(Constants.Scale.HEALTH_UNHEALTHY_TIMEOUT_SECONDS),
    val progressInterval: Duration = Duration.ofSeconds(Constants.Scale.PROGRESS_INTERVAL_SECONDS),
) {
    /** One attempt, replaceable: the instance waits and SSH. */
    private fun once(timeout: Duration) = StepPolicy(1, timeout, replaceable = true)

    val runningPolicy: StepPolicy get() = once(running)
    val statusPolicy: StepPolicy get() = once(statusChecks)

    /** The SSH poll's own deadline decides; the step's bound only backs it up. */
    val sshPolicy: StepPolicy get() = once(sshTimeout.plus(sshInterval).plus(sshInterval))

    /**
     * Running the image check: retried, then replaced, since a check that could not run says
     * nothing about the image. An image found to lack the fixes is never replaced ([InstanceRun.checkImage]).
     */
    val imageCheckPolicy: StepPolicy get() = StepPolicy(Constants.Scale.IMAGE_CHECK_ATTEMPTS, imageCheck, replaceable = true)

    /** A control node step: retried, never replaced. */
    val controlPolicy: StepPolicy get() = StepPolicy(Constants.Scale.CONTROL_STEP_ATTEMPTS, controlStep, replaceable = false)

    /** The gate's own deadline decides; the step's bound only backs it up. */
    val gatePolicy: StepPolicy get() = StepPolicy(1, gateTimeout.plus(gateInterval).plus(controlStep), replaceable = false)
}
