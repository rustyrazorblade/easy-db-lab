package com.rustyrazorblade.easydblab.configuration

import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder

/**
 * How the observability backends' and Grafana's Services reach callers outside the pod network: the
 * CLI under Socks or Direct exposure, browsers, redirect senders, EMR and operator scripts. None of
 * those resolves cluster DNS, so on nodes easy-db-lab owns each client port is published on a fixed
 * NodePort and the Service lists only those ports; on a cluster it does not own, fixed node ports
 * could collide or be refused, so the Services stay ClusterIP and the CLI reaches them by
 * port-forward.
 */
enum class ServicePublishing {
    /** The client ports on fixed NodePorts, open on every node of a cluster easy-db-lab built. */
    NodePorts,

    /** Cluster-internal only: an environment that does not own its nodes. */
    ClusterIp,
    ;

    /**
     * [service] published this way. [nodePorts] maps a Service port name to its fixed node port.
     *
     * Under [NodePorts] the Service lists only the mapped ports: Kubernetes gives every port of a
     * NodePort Service a node port, picking a random one in 30000-32767 when none is set, which would
     * publish an internal port (gRPC, OTLP HTTP and the like) and could take a port a kit claims
     * later. Nothing dials those ports through the Service; the pod still listens on them. A mapped
     * name the Service does not have is refused rather than silently left unpublished.
     */
    fun publish(
        service: Service,
        nodePorts: Map<String, Int>,
    ): Service =
        when (this) {
            NodePorts -> {
                val names =
                    service.spec.ports
                        .map { it.name }
                        .toSet()
                val unknown = nodePorts.keys - names
                require(unknown.isEmpty()) {
                    "Service ${service.metadata.name} has no port named $unknown to publish; its ports are $names"
                }
                ServiceBuilder(service)
                    .editSpec()
                    .withType("NodePort")
                    .withPorts(
                        service.spec.ports.mapNotNull { port ->
                            nodePorts[port.name]?.let { ServicePortBuilder(port).withNodePort(it).build() }
                        },
                    ).endSpec()
                    .build()
            }
            ClusterIp -> service
        }
}
