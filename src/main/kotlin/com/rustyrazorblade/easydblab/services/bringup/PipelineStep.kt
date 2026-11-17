package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import java.time.Duration

/**
 * The steps of a node's pipeline, in the order a node moves through them. [wireName] is the name
 * events and the progress line carry. The control node's own steps sit between the shared ones
 * where it runs them; a database or application node never enters them.
 */
enum class PipelineStep(
    val wireName: String,
) {
    RUNNING("running"),
    STATUS_CHECKS("status-checks"),
    SSH("ssh"),
    WORKSPACE("workspace"),
    SETUP("setup"),
    IMAGE_CHECK("image-check"),
    TUNNEL("tunnel"),
    REGISTRY_CERT("registry-cert"),
    REGISTRY_TLS("registry-tls"),
    CONTROL_PLANE("control-plane"),
    CNI("cni"),
    GATE("gate"),
    JOIN_WAIT("join-wait"),
    JOIN("join"),
    READINESS("readiness"),
    REPLACING("replacing"),
}

/**
 * How one step is retried: [attempts] in all, each bounded by [timeout]. [replaceable] says
 * whether a new instance can fix a step whose attempts ran out (a Cilium image check cannot: the
 * replacement has the same AMI). With [forgiveWhileUnhealthy], a failure while the control plane
 * was unhealthy is not counted: the step waits for health and runs the attempt again.
 */
data class StepPolicy(
    val attempts: Int,
    val timeout: Duration,
    val replaceable: Boolean,
    val forgiveWhileUnhealthy: Boolean = false,
) {
    init {
        require(attempts >= 1) { "a step needs at least one attempt, got $attempts" }
    }

    /** The policies of design D5. */
    companion object {
        val SETUP = StepPolicy(2, Duration.ofSeconds(Constants.Scale.NODE_SETUP_TIMEOUT_SECONDS), replaceable = true)
        val REGISTRY_TLS = StepPolicy(3, Duration.ofSeconds(Constants.Scale.REGISTRY_TLS_TIMEOUT_SECONDS), replaceable = true)
        val JOIN =
            StepPolicy(
                2,
                Duration.ofSeconds(Constants.Scale.NODE_REGISTRATION_TIMEOUT_SECONDS),
                replaceable = true,
                forgiveWhileUnhealthy = true,
            )
        val READY =
            StepPolicy(
                1,
                Duration.ofSeconds(Constants.Scale.NODE_READY_TIMEOUT_SECONDS),
                replaceable = true,
                forgiveWhileUnhealthy = true,
            )
    }
}

/** How a step ended. */
sealed interface StepOutcome<out T> {
    /** The step succeeded with [value]. */
    data class Succeeded<T>(
        val value: T,
    ) : StepOutcome<T>

    /** Every attempt failed; [reason] is the last one's. */
    data class Failed(
        val reason: String,
        val replaceable: Boolean,
    ) : StepOutcome<Nothing>

    /** The provider ended the node's instance during the step; [state] is what it reported. */
    data class InstanceEnded(
        val state: String,
    ) : StepOutcome<Nothing>
}
