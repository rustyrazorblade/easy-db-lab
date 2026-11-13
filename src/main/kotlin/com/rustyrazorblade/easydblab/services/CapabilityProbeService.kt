package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CapabilityProbePods
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import java.util.concurrent.TimeUnit

/**
 * What a probe found: the admission capabilities the namespace admits, and a note for each one it
 * refused.
 *
 * @property capabilities the probed set: the admitted admission capabilities only. `NODE_SSH` is
 *   never in it, and nor are the capabilities the probe does not decide (`SYSTEMD_JOURNAL`,
 *   `CLOUDWATCH`, `S3`), which the environment declares.
 * @property notes why each absent admission capability is absent, in the API server's words
 */
data class ProbeResult(
    val capabilities: NodeCapabilities,
    val notes: Map<NodeCapability, String>,
)

/**
 * Learns what a cluster the user brings admits in one namespace, by submitting Pods with server-side
 * dry run (`dryRun=All`). Pod Security admission, GKE Autopilot and policy engines decide at
 * admission time and per namespace, so a declared value can be wrong there; asking the API server is
 * the only way to know.
 *
 * The restricted baseline Pod goes first: when it is rejected the namespace or the credentials admit
 * no Pod at all, which is not a capability fact, and the probe fails. Then each admission
 * capability's Pod ([CapabilityProbePods]) is submitted; [ProbeRejection] decides what a rejection
 * means. Nothing is persisted except the namespace, which the probe creates when it is missing (a
 * dry run in a missing namespace answers 404). Version 1 needs cluster-admin for that.
 *
 * Not called on AWS or local Docker, whose capabilities are declared. `init --kubeconfig` (#1082)
 * calls it.
 *
 * @param clientFactory builds the client for the cluster named by the [KubeApi]
 */
class CapabilityProbeService(
    private val clientFactory: KubernetesClientFactory,
) {
    /**
     * Probes [namespace] on the cluster [api] names.
     *
     * @throws IllegalStateException naming the namespace and the API server's message when the
     *   baseline Pod is rejected or any request fails in a way that says nothing about a capability
     */
    fun probe(
        api: KubeApi,
        namespace: String,
    ): ProbeResult =
        clientFactory.createClient(api).use { client ->
            ensureNamespace(client, namespace)
            when (val baseline = submit(client, CapabilityProbePods.baseline(namespace))) {
                null -> Unit
                else -> fail(namespace, "the restricted baseline Pod was rejected: ${baseline.message}")
            }
            val refused =
                CapabilityProbePods.probes(namespace).mapNotNull { (capability, pod) ->
                    submit(client, pod)?.let { rejection ->
                        when (val outcome = ProbeRejection.classify(rejection.code, rejection.message)) {
                            is ProbeRejection.Refused -> capability to outcome.note
                            is ProbeRejection.Unexpected -> fail(namespace, "probing ${capability.serialName}: ${outcome.message}")
                        }
                    }
                }.toMap()
            val admitted = CapabilityProbePods.probes(namespace).keys - refused.keys
            ProbeResult(NodeCapabilities(admitted), refused)
        }

    /** The API server's answer to a rejected request. */
    private data class Rejection(
        val code: Int,
        val message: String,
    )

    /** Submits [pod] as a dry run; null when admitted. */
    private fun submit(
        client: KubernetesClient,
        pod: Pod,
    ): Rejection? =
        try {
            client
                .pods()
                .inNamespace(pod.metadata.namespace)
                .resource(pod)
                .dryRun()
                .create()
            null
        } catch (e: KubernetesClientException) {
            Rejection(e.code, e.status?.message ?: e.message.orEmpty())
        }

    private fun ensureNamespace(
        client: KubernetesClient,
        namespace: String,
    ) {
        if (client.namespaces().withName(namespace).get() != null) return
        try {
            client
                .namespaces()
                .resource(
                    NamespaceBuilder()
                        .withNewMetadata()
                        .withName(namespace)
                        .endMetadata()
                        .build(),
                ).create()
        } catch (e: KubernetesClientException) {
            // Another caller created it between the read and the create.
            if (e.code != CONFLICT) fail(namespace, "creating the namespace: ${e.status?.message ?: e.message}")
        }
        // Until its default ServiceAccount exists, the API server rejects every Pod in a new namespace
        // with 403, which would read as a refused capability.
        client
            .serviceAccounts()
            .inNamespace(namespace)
            .withName(DEFAULT_SERVICE_ACCOUNT)
            .waitUntilCondition({ it != null }, Constants.Capabilities.NAMESPACE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun fail(
        namespace: String,
        reason: String,
    ): Nothing = error("The capability probe failed in namespace '$namespace': $reason")

    private companion object {
        /** HTTP 409, what the API server answers a create of an existing object with. */
        const val CONFLICT = 409

        const val DEFAULT_SERVICE_ACCOUNT = "default"
    }
}
