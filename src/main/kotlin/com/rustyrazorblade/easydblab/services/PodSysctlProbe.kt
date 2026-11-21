package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.core.JsonProcessingException
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.kernel.causeChain
import com.rustyrazorblade.easydblab.kubernetes.KubeconfigNotFoundException
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.reason
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * What a server-side dry-run pod came back with. `up` probes again until the deadline after any
 * result that lacks a tuned value, a refusal and an unreachable server included: right after K3s
 * starts, a refusal can be transient (the `default` ServiceAccount does not exist yet).
 */
sealed interface PodSysctlProbeResult {
    /** The API server admitted the pod; [sysctls] is its `spec.securityContext.sysctls`, name to value. */
    data class Admitted(
        val sysctls: Map<String, String>,
    ) : PodSysctlProbeResult

    /** The API server refused the pod; [reason] is its message. */
    data class Refused(
        val reason: String,
    ) : PodSysctlProbeResult

    /** The API server could not be reached (a transport failure); [reason] says why. */
    data class Unreachable(
        val reason: String,
    ) : PodSysctlProbeResult
}

/**
 * Asks the API server what a new pod-network pod would look like after admission, without making
 * one (pod-network-tuning, design D5). Needs no node, no kubelet and no image.
 */
fun interface PodSysctlProbe {
    /** Creates a dry-run pod and returns the sysctls admission gave it, or why it was refused. */
    fun probe(): PodSysctlProbeResult
}

/**
 * [PodSysctlProbe] over fabric8: a `dryRun=All` create of a plain pod in the namespace easy-db-lab
 * installs into, which [namespace] resolves on each probe.
 *
 * An answer from the API server that refuses the pod is a [PodSysctlProbeResult.Refused]; a client exception
 * with no HTTP status that a transport failure caused is a [PodSysctlProbeResult.Unreachable] (see [resultOf]).
 * So is a workspace with no kubeconfig, or one that could not be read, so it goes through the same
 * retry, deadline and typed event in [PodNetworkTuning]; a kubeconfig that does not parse is a defect
 * and throws. fabric8 wraps a transport failure
 * below it in a [KubernetesClientException] with no status, which [resultOf] already maps.
 */
class Fabric8PodSysctlProbe(
    private val clientProvider: K8sClientProvider,
    private val namespace: () -> K8sNamespace,
) : PodSysctlProbe {
    /**
     * Only a workspace with no kubeconfig, or one that could not be read, is an unreachable cluster
     * here; any other failure to make the client, or a defect in the request, still throws.
     */
    override fun probe(): PodSysctlProbeResult {
        val client =
            try {
                clientProvider.createClient()
            } catch (e: IOException) {
                return PodSysctlProbeResult.Unreachable(e.reason())
            } catch (e: KubeconfigNotFoundException) {
                return PodSysctlProbeResult.Unreachable(e.reason())
            }
        return client.use {
            try {
                PodSysctlProbeResult.Admitted(sysctlsOf(admit(it, probePod())))
            } catch (e: KubernetesClientException) {
                resultOf(e)
            }
        }
    }

    /** [pod] as the API server would create it, after every admission plugin and policy; nothing is created. */
    private fun admit(
        client: KubernetesClient,
        pod: Pod,
    ): Pod =
        client
            .pods()
            .inNamespace(namespace().value)
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
         * the code to -1 and no status) the server was not reached only when a transport failure
         * caused it: an I/O failure, or fabric8's own request timeout.
         *
         * @throws KubernetesClientException [e] itself when no transport failure caused it (an answer
         *   the client could not read, for one): that is a defect, not an unreachable server
         */
        fun resultOf(e: KubernetesClientException): PodSysctlProbeResult =
            when {
                e.status != null || e.code > 0 -> PodSysctlProbeResult.Refused(e.status?.message ?: e.messageOrString())
                e.causeChain().any(::isTransportFailure) -> PodSysctlProbeResult.Unreachable(e.messageOrString())
                else -> throw e
            }

        /** A failure to send the request or get its answer; a body Jackson could not parse is not one. */
        private fun isTransportFailure(cause: Throwable): Boolean =
            when (cause) {
                is JsonProcessingException -> false
                is IOException, is TimeoutException -> true
                else -> false
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
