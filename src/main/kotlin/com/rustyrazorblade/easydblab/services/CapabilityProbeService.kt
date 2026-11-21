package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CapabilityProbePods
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException

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
 * dry run in a missing namespace answers 404), and the namespace's `default` ServiceAccount, which
 * it creates rather than waiting for the controller. Version 1 needs cluster-admin for that.
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
                else -> fail(namespace, "the restricted baseline Pod was rejected: ${baseline.message}", baseline.exception)
            }
            val refused =
                CapabilityProbePods
                    .probes(namespace)
                    .mapNotNull { (capability, pod) ->
                        submit(client, pod)?.let { rejection ->
                            when (val outcome = ProbeRejection.classify(rejection.code, rejection.message)) {
                                is ProbeRejection.Refused -> capability to outcome.note
                                is ProbeRejection.Unexpected ->
                                    fail(namespace, "probing ${capability.serialName}: ${outcome.message}", rejection.exception)
                            }
                        }
                    }.toMap()
            val admitted = CapabilityProbePods.probes(namespace).keys - refused.keys
            ProbeResult(NodeCapabilities(admitted), refused)
        }

    /** The API server's answer to a rejected request, and the exception that carried it. */
    private data class Rejection(
        val code: Int,
        val message: String,
        val exception: KubernetesClientException,
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
            Rejection(e.code, e.serverMessage(), e)
        }

    private fun ensureNamespace(
        client: KubernetesClient,
        namespace: String,
    ) {
        val existing =
            try {
                client.namespaces().withName(namespace).get()
            } catch (e: KubernetesClientException) {
                fail(namespace, "reading the namespace: ${e.serverMessage()}", e)
            }
        if (existing == null) {
            createIfAbsent(namespace, "creating the namespace") {
                client
                    .namespaces()
                    .resource(
                        NamespaceBuilder()
                            .withNewMetadata()
                            .withName(namespace)
                            .endMetadata()
                            .build(),
                    ).create()
            }
        }
        ensureDefaultServiceAccount(client, namespace)
    }

    /**
     * Until its default ServiceAccount exists, the API server rejects every Pod in a namespace with
     * 403, which would read as a refused capability. The kube-controller-manager creates it
     * asynchronously, and on a busy cluster it lags by over a minute, so the probe creates it itself
     * instead of waiting: the controller only ever creates the same empty ServiceAccount, and an
     * existing one (the controller won, or the namespace is old) is left as it is.
     */
    private fun ensureDefaultServiceAccount(
        client: KubernetesClient,
        namespace: String,
    ) = createIfAbsent(namespace, "creating the default ServiceAccount") {
        client
            .serviceAccounts()
            .inNamespace(namespace)
            .resource(
                ServiceAccountBuilder()
                    .withNewMetadata()
                    .withName(DEFAULT_SERVICE_ACCOUNT)
                    .endMetadata()
                    .build(),
            ).create()
    }

    /** Runs [create]; an object that already exists (409) is success, any other error fails naming [step]. */
    private fun createIfAbsent(
        namespace: String,
        step: String,
        create: () -> Unit,
    ) {
        try {
            create()
        } catch (e: KubernetesClientException) {
            if (e.code != Constants.HttpStatus.CONFLICT) fail(namespace, "$step: ${e.serverMessage()}", e)
        }
    }

    /** The API server's own message, without Fabric8's request prefix. */
    private fun KubernetesClientException.serverMessage(): String = status?.message ?: message.orEmpty()

    /**
     * Fails naming [namespace] and [reason], keeping the Fabric8 [cause] so the Kubernetes error
     * handler describes it: a 403 there names the denied request and the cluster-admin requirement,
     * and a Pod Security rejection of the baseline names its level and violations.
     */
    private fun fail(
        namespace: String,
        reason: String,
        cause: KubernetesClientException,
    ): Nothing = throw IllegalStateException("The capability probe failed in namespace '$namespace': $reason", cause)

    private companion object {
        const val DEFAULT_SERVICE_ACCOUNT = "default"
    }
}
