package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.PodNetworkSysctlPolicyBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.reason
import com.rustyrazorblade.easydblab.services.bringup.pollUntil
import java.time.Duration

/**
 * Puts the pod network sysctls in effect for every later pod, once per `up` (pod-network-tuning,
 * designs D1 and D5).
 *
 * It applies the cluster-wide admission policy, then asks the API server for a dry-run pod until
 * that pod carries every injected value. The API server loads a new policy asynchronously, so the
 * first pods after the apply can still be untuned; waiting here means no kit pod ever is. Past
 * [readyTimeout] `up` fails, naming the keys the pod still lacks.
 *
 * @param sysctls the keys and values every pod-network pod gets, from [PodNetworkSysctls]
 */
class PodNetworkTuning(
    private val manifests: K8sManifestOperations,
    private val probe: PodSysctlProbe,
    private val eventBus: EventBus,
    private val sysctls: Map<String, String> = PodNetworkSysctls.packaged(),
    private val readyTimeout: Duration = Duration.ofSeconds(Constants.NodeTuning.POD_SYSCTL_POLICY_READY_SECONDS),
    private val pollInterval: Duration = Duration.ofSeconds(Constants.NodeTuning.POD_SYSCTL_POLICY_POLL_SECONDS),
) {
    /**
     * Applies the policy and waits until it is in effect.
     *
     * @throws CommandFailedException when the policy cannot be applied, or a dry-run pod still lacks
     *   a value after [readyTimeout]
     */
    fun apply() {
        PodNetworkSysctlPolicyBuilder(sysctls).buildAll().forEach { resource ->
            manifests.applyResource(resource).getOrElse { failure ->
                val reason = failure.reason()
                eventBus.emit(Event.Platform.PodSysctlPolicyNotApplied(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, reason))
                throw CommandFailedException("The pod network sysctl policy could not be applied: $reason", failure)
            }
        }
        val last =
            pollUntil<ProbeResult>(
                "pod-sysctl-policy",
                pollInterval,
                readyTimeout,
                done = { missing(it).isEmpty() },
            ) { probe.probe() }
        val missing = missing(last)
        if (missing.isNotEmpty()) {
            val refusal = (last as? ProbeResult.Refused)?.reason
            eventBus.emit(Event.Platform.PodSysctlPolicyNotInEffect(missing, readyTimeout.seconds, refusal))
            throw CommandFailedException(
                "The pod network sysctl policy was not in effect after ${readyTimeout.seconds}s; a new pod lacks $missing",
            )
        }
        eventBus.emit(Event.Platform.PodSysctlPolicyReady(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, sysctls))
    }

    /** The injected keys [result] does not carry with the injected value; every key when the pod was refused. */
    private fun missing(result: ProbeResult): List<String> =
        when (result) {
            is ProbeResult.Refused -> sysctls.keys.toList()
            is ProbeResult.Admitted -> sysctls.filter { (key, value) -> result.sysctls[key] != value }.keys.toList()
        }
}
