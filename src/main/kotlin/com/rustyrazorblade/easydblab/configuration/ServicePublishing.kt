package com.rustyrazorblade.easydblab.configuration

import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder

/**
 * How the observability backends' and Grafana's Services reach callers outside the pod network: the
 * CLI under Socks or Direct exposure, browsers, redirect senders, EMR and operator scripts. None of
 * those resolves cluster DNS, so on nodes easy-db-lab owns each client port is published on a fixed
 * NodePort; on a cluster it does not own, fixed node ports could collide or be refused, so the
 * Services stay ClusterIP and the CLI reaches them by port-forward.
 */
enum class ServicePublishing {
    /** The client ports on fixed NodePorts, open on every node of a cluster easy-db-lab built. */
    NodePorts,

    /** Cluster-internal only: an environment that does not own its nodes. */
    ClusterIp,
    ;

    /**
     * [service] published this way. [nodePorts] maps a Service port name to its fixed node port; a
     * port not named there (gRPC, gossip and the like) never gets one.
     */
    fun publish(
        service: Service,
        nodePorts: Map<String, Int>,
    ): Service =
        when (this) {
            NodePorts ->
                ServiceBuilder(service)
                    .editSpec()
                    .withType("NodePort")
                    .withPorts(
                        service.spec.ports.map { port ->
                            ServicePortBuilder(port).withNodePort(nodePorts[port.name]).build()
                        },
                    ).endSpec()
                    .build()
            ClusterIp -> service
        }
}
