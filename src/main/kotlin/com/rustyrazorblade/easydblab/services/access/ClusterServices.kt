package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace

/**
 * One observability Service the CLI reads, without its namespace: the Service name and port, and
 * the node role and fixed NodePort it is published on (on nodes easy-db-lab owns). The namespace is
 * the cluster's, known only from its state, so a caller makes the [ServiceTarget] with [inNamespace].
 *
 * @property service the Service's name
 * @property servicePort the Service port a port-forward opens
 * @property nodeRole the node type that runs the workload
 * @property nodePort the Service's NodePort, open at that node's address
 */
data class ClusterService(
    val service: String,
    val servicePort: Int,
    val nodeRole: ServerType,
    val nodePort: Int,
) {
    /** This Service in [namespace], the target [ServiceAccess] opens. */
    fun inNamespace(namespace: K8sNamespace): ServiceTarget = ServiceTarget(namespace.value, service, servicePort, nodeRole, nodePort)
}

/**
 * The observability Services the CLI reads. Clients take their target from here, never an address.
 */
object ClusterServices {
    val MIMIR = controlService(Constants.K8s.MIMIR_APP_LABEL, Constants.K8s.MIMIR_HTTP_PORT, Constants.K8s.MIMIR_NODE_PORT)
    val LOKI = controlService(Constants.K8s.LOKI_APP_LABEL, Constants.K8s.LOKI_HTTP_PORT, Constants.K8s.LOKI_NODE_PORT)
    val TEMPO = controlService(Constants.K8s.TEMPO_APP_LABEL, Constants.K8s.TEMPO_PORT, Constants.K8s.TEMPO_NODE_PORT)
    val PYROSCOPE = controlService(Constants.K8s.PYROSCOPE_APP_LABEL, Constants.K8s.PYROSCOPE_PORT, Constants.K8s.PYROSCOPE_NODE_PORT)
    val GRAFANA = controlService(Constants.K8s.GRAFANA_APP_LABEL, Constants.K8s.GRAFANA_PORT, Constants.K8s.GRAFANA_NODE_PORT)

    /** Every catalogued Service, in the order `status` lists them. */
    val ALL = listOf(GRAFANA, MIMIR, LOKI, TEMPO, PYROSCOPE)

    private fun controlService(
        name: String,
        servicePort: Int,
        nodePort: Int,
    ) = ClusterService(name, servicePort, ServerType.Control, nodePort)
}
