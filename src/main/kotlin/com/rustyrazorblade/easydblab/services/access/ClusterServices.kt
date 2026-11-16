package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType

/**
 * The observability Services the CLI reads, each with its Kubernetes Service port and the fixed
 * NodePort it is published on (on nodes easy-db-lab owns). Clients take their target from here,
 * never an address.
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
    ) = ServiceTarget(Constants.K8s.NAMESPACE, name, servicePort, ServerType.Control, nodePort)
}
