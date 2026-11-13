package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType

/**
 * The observability Services the CLI reads, each with its Kubernetes Service and the control-node
 * port its host-network pod listens on. Clients take their target from here, never an address.
 */
object ClusterServices {
    val MIMIR = controlService(Constants.K8s.MIMIR_APP_LABEL, Constants.K8s.MIMIR_HTTP_PORT)
    val LOKI = controlService(Constants.K8s.LOKI_APP_LABEL, Constants.K8s.LOKI_HTTP_PORT)
    val TEMPO = controlService(Constants.K8s.TEMPO_APP_LABEL, Constants.K8s.TEMPO_PORT)
    val PYROSCOPE = controlService(Constants.K8s.PYROSCOPE_APP_LABEL, Constants.K8s.PYROSCOPE_PORT)
    val GRAFANA = controlService(Constants.K8s.GRAFANA_APP_LABEL, Constants.K8s.GRAFANA_PORT)

    /** Every catalogued Service, in the order `status` lists them. */
    val ALL = listOf(GRAFANA, MIMIR, LOKI, TEMPO, PYROSCOPE)

    /** The catalogued Service whose Service port is [port]; observability readers address them by port. */
    fun byPort(port: Int): ServiceTarget =
        ALL.firstOrNull { it.servicePort == port } ?: error("No cluster Service is catalogued on port $port")

    private fun controlService(
        name: String,
        port: Int,
    ) = ServiceTarget(Constants.K8s.NAMESPACE, name, port, ServerType.Control, port)
}
