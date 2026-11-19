package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.reason
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import java.io.IOException

/**
 * What a server-side dry-run pod came back with. `up` probes again until the deadline after any
 * result that lacks a tuned value, a refusal and an unreachable server included: right after K3s
 * starts, a refusal can be transient (the `default` ServiceAccount does not exist yet).
 */
sealed interface ProbeResult {
    /** The API server admitted the pod; [sysctls] is its `spec.securityContext.sysctls`, name to value. */
    data class Admitted(
        val sysctls: Map<String, String>,
    ) : ProbeResult

    /** The API server refused the pod; [reason] is its message. */
    data class Refused(
        val reason: String,
    ) : ProbeResult

    /** The API server could not be reached (a transport failure); [reason] says why. */
    data class Unreachable(
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
 * An answer from the API server that refuses the pod is a [ProbeResult.Refused]; a client exception
 * with no HTTP status (the cluster was not reached) is a [ProbeResult.Unreachable] (see [resultOf]).
 * So is a client that could not be made, so a workspace with no usable kubeconfig goes through the
 * same retry, deadline and typed event in [PodNetworkTuning]. fabric8 wraps a transport failure
 * below it in a [KubernetesClientException] with no status, which [resultOf] already maps.
 */
class Fabric8PodSysctlProbe(
    private val clientProvider: K8sClientProvider,
) : PodSysctlProbe {
    /**
     * Only a failure to make the client (the workspace has no kubeconfig, or it could not be read or
     * parsed) is an unreachable cluster here; a defect in the request itself still throws.
     */
    override fun probe(): ProbeResult {
        val client =
            try {
                clientProvider.createClient()
            } catch (e: IOException) {
                return ProbeResult.Unreachable(e.reason())
            } catch (e: IllegalStateException) {
                return ProbeResult.Unreachable(e.reason())
            }
        return client.use {
            try {
                ProbeResult.Admitted(sysctlsOf(admit(it, probePod())))
            } catch (e: KubernetesClientException) {
                resultOf(e)
            }
        }
    }

    /** [pod] as the API server would create it, after every admission plugin and policy; nothing is created. */
    fun admit(pod: Pod): Pod = clientProvider.createClient().use { admit(it, pod) }

    private fun admit(
        client: KubernetesClient,
        pod: Pod,
    ): Pod =
        client
            .pods()
            .inNamespace(Constants.NodeTuning.POD_SYSCTL_PROBE_NAMESPACE)
            .resource(pod)
            .dryRun()
            .create()

    private fun probePod() =
        PodBuilder()
            .withNewMetadata()
            .withGenerateName(Constants.NodeTuning.POD_SYSCTL_PROBE_NAME_PREFIX)
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("probe")
            .withImage(Constants.NodeTuning.POD_SYSCTL_PROBE_IMAGE)
            .endContainer()
            .endSpec()
            .build()

    companion object {
        /**
         * An answer with an HTTP status is the API server refusing the pod. With none (fabric8 sets
         * the code to -1 and no status when the request never got an answer) the server was not reached.
         */
        fun resultOf(e: KubernetesClientException): ProbeResult =
            when {
                e.status == null && e.code <= 0 -> ProbeResult.Unreachable(e.messageOrString())
                else -> ProbeResult.Refused(e.status?.message ?: e.messageOrString())
            }

        /** [pod]'s `spec.securityContext.sysctls`, name to value. */
        fun sysctlsOf(pod: Pod): Map<String, String> =
            pod.spec
                ?.securityContext
                ?.sysctls
                .orEmpty()
                .associate { it.name to it.value }
    }
}
