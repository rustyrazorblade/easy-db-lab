package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * [ControlPlaneReader] on a fabric8 client of its own from [K8sClientProvider], so it goes
 * through the per-client SOCKS tunnel. A read the API server refuses, or that cannot reach it,
 * reads as not ready: while K3s starts, refusals are expected, and the gate's deadline decides.
 */
class Fabric8ControlPlaneReader private constructor(
    private val client: KubernetesClient,
) : ControlPlaneReader,
    AutoCloseable {
    override fun apiServerReady(): Boolean = read("/readyz", false) { client.raw(READYZ)?.trim() == OK }

    override fun nodeRegistered(alias: String): Boolean = read("node $alias", false) { client.nodes().withName(alias).get() != null }

    override fun operatorReplicas(): ControlPlaneReader.Replicas? =
        read("the Cilium operator", null) {
            client
                .apps()
                .deployments()
                .inNamespace(Constants.Cilium.NAMESPACE)
                .withName(Constants.Cilium.OPERATOR_DEPLOYMENT)
                .get()
                ?.let { ControlPlaneReader.Replicas(it.status?.readyReplicas ?: 0, it.spec?.replicas ?: 1) }
        }

    override fun establishedCrds(names: List<String>): Set<String> =
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

    /** Opening a reader. */
    companion object {
        private val log = KotlinLogging.logger {}
        private const val READYZ = "/readyz"
        private const val OK = "ok"
        private const val ESTABLISHED = "Established"
        private const val TRUE = "True"

        /** Opens a reader on a client of its own from [clientProvider]; close it when the gate is done. */
        fun open(clientProvider: K8sClientProvider): Fabric8ControlPlaneReader = Fabric8ControlPlaneReader(clientProvider.createClient())

        private fun isEstablished(crd: CustomResourceDefinition): Boolean =
            crd.status
                ?.conditions
                .orEmpty()
                .any { it.type == ESTABLISHED && it.status == TRUE }
    }
}
