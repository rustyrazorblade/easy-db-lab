package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.messageOrString
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClientException

/** What a server-side dry-run pod came back with. */
sealed interface ProbeResult {
    /** The API server admitted the pod; [sysctls] is its `spec.securityContext.sysctls`, name to value. */
    data class Admitted(
        val sysctls: Map<String, String>,
    ) : ProbeResult

    /** The API server refused the pod; [reason] is its message. */
    data class Refused(
        val reason: String,
    ) : ProbeResult
}

/**
 * Asks the API server what a new pod-network pod would look like after admission, without making
 * one (pod-network-tuning, design D5). Needs no node, no kubelet and no image.
 */
fun interface PodSysctlProbe {
    /** Creates a dry-run pod and returns the sysctls admission gave it, or why it was refused. */
    fun probe(): ProbeResult
}

/**
 * [PodSysctlProbe] over fabric8: a `dryRun=All` create of a plain pod in
 * [Constants.NodeTuning.POD_SYSCTL_PROBE_NAMESPACE].
 *
 * A failure to reach the cluster propagates; only a refusal by the API server is a [ProbeResult.Refused].
 */
class Fabric8PodSysctlProbe(
    private val clientProvider: K8sClientProvider,
) : PodSysctlProbe {
    override fun probe(): ProbeResult =
        try {
            ProbeResult.Admitted(sysctlsOf(admit(probePod())))
        } catch (e: KubernetesClientException) {
            if (e.code == 0) throw e
            ProbeResult.Refused(e.status?.message ?: e.messageOrString())
        }

    /** [pod] as the API server would create it, after every admission plugin and policy; nothing is created. */
    fun admit(pod: Pod): Pod =
        clientProvider.createClient().use { client ->
            client
                .pods()
                .inNamespace(Constants.NodeTuning.POD_SYSCTL_PROBE_NAMESPACE)
                .resource(pod)
                .dryRun()
                .create()
        }

    private fun probePod() =
        PodBuilder()
            .withNewMetadata()
            .withName(Constants.NodeTuning.POD_SYSCTL_PROBE_NAME)
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("probe")
            .withImage(Constants.NodeTuning.POD_SYSCTL_PROBE_IMAGE)
            .endContainer()
            .endSpec()
            .build()

    companion object {
        /** [pod]'s `spec.securityContext.sysctls`, name to value. */
        fun sysctlsOf(pod: Pod): Map<String, String> =
            pod.spec
                ?.securityContext
                ?.sysctls
                .orEmpty()
                .associate { it.name to it.value }
    }
}
