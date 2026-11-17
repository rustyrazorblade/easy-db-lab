package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneReader
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * [ControlPlaneReader] on a fabric8 client of its own from [K8sClientProvider], so it goes
 * through the per-client SOCKS tunnel. A read the API server refuses, or that cannot reach it,
 * reads as not ready: while K3s starts, refusals are expected, and the gate's deadline decides.
 *
 * Under Cilium the network conditions are the operator's ready replicas and every CRD in
 * [Constants.Cilium.REQUIRED_CRDS] `Established`; under Flannel there are none.
 */
class Fabric8ControlPlaneReader private constructor(
    private val client: KubernetesClient,
    private val cni: CniMode,
) : ControlPlaneReader,
    AutoCloseable {
    override fun apiServerReady(): Boolean = read("/readyz", false) { client.raw(READYZ)?.trim() == OK }

    override fun nodeRegistered(alias: String): Boolean = read("node $alias", false) { client.nodes().withName(alias).get() != null }

    override fun networkConditions(): Map<String, Boolean> =
        when (cni) {
            CniMode.Flannel -> emptyMap()
            CniMode.Cilium -> ciliumConditions(operatorReplicas(), establishedCrds(Constants.Cilium.REQUIRED_CRDS))
        }

    /** The ready replicas of the Cilium operator Deployment, and how many it wants; null when it does not exist. */
    fun operatorReplicas(): Replicas? =
        read("the Cilium operator", null) {
            client
                .apps()
                .deployments()
                .inNamespace(Constants.Cilium.NAMESPACE)
                .withName(Constants.Cilium.OPERATOR_DEPLOYMENT)
                .get()
                ?.let { Replicas(it.status?.readyReplicas ?: 0, it.spec?.replicas ?: 1) }
        }

    /** Which of [names] exist with the `Established` condition `True`. */
    fun establishedCrds(names: List<String>): Set<String> =
        read("the Cilium CRDs", emptySet()) {
            client
                .apiextensions()
                .v1()
                .customResourceDefinitions()
                .list()
                .items
                .filter { crd -> crd.metadata.name in names && isEstablished(crd) }
                .map { it.metadata.name }
                .toSet()
        }

    override fun close() = client.close()

    private fun <T> read(
        what: String,
        notReady: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: KubernetesClientException) {
            log.debug(e) { "Could not read $what yet" }
            notReady
        }

    /** A Deployment's ready and desired replicas. */
    data class Replicas(
        val ready: Int,
        val desired: Int,
    )

    /** Opening a reader, and the Cilium conditions it reports. */
    companion object {
        private val log = KotlinLogging.logger {}
        private const val READYZ = "/readyz"
        private const val OK = "ok"
        private const val ESTABLISHED = "Established"
        private const val TRUE = "True"

        /** Opens a reader for a [cni] cluster on a client of its own from [clientProvider]; close it when the gate is done. */
        fun open(
            clientProvider: K8sClientProvider,
            cni: CniMode,
        ): Fabric8ControlPlaneReader = Fabric8ControlPlaneReader(clientProvider.createClient(), cni)

        /**
         * The Cilium conditions, by name: the operator has all its desired replicas ready (a
         * Deployment that wants none is not ready), and each required CRD is in [established].
         */
        fun ciliumConditions(
            operator: Replicas?,
            established: Set<String>,
        ): Map<String, Boolean> {
            val operatorReady = operator != null && operator.desired > 0 && operator.ready >= operator.desired
            return mapOf("${Constants.Cilium.OPERATOR_DEPLOYMENT} ready" to operatorReady) +
                Constants.Cilium.REQUIRED_CRDS.associate { "CRD $it established" to (it in established) }
        }

        private fun isEstablished(crd: CustomResourceDefinition): Boolean =
            crd.status
                ?.conditions
                .orEmpty()
                .any { it.type == ESTABLISHED && it.status == TRUE }
    }
}
