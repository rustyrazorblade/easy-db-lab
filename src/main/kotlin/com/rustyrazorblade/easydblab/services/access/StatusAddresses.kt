package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitEndpointAddresses
import com.rustyrazorblade.easydblab.services.KitRuntime
import com.rustyrazorblade.easydblab.services.installConfigYaml
import com.rustyrazorblade.easydblab.services.sql.KitService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * An address `status` prints, and on a port-forward cluster the command that makes it reachable.
 *
 * @property address the address, or the Service DNS name on a port-forward cluster
 * @property hint a ready-to-run `bin/kubectl port-forward` command, or null when the address is reachable as it is
 */
data class StatusAddress(
    val address: String,
    val hint: String? = null,
)

/**
 * One declared kit endpoint and one address `status` prints for it.
 *
 * @property endpoint the endpoint the kit declares
 * @property address where it is reached, with its forward command on a port-forward cluster
 */
data class ShownEndpoint(
    val endpoint: KitEndpoint,
    val address: StatusAddress,
) {
    /** The `name  type  address` line `status` prints, then the forward command when there is one. */
    fun displayLines(): String {
        val line = Event.Kit.EndpointAddress(endpoint.name, endpoint.type.name.lowercase(), address.address).displayLine()
        return address.hint?.let { "$line\n  %-20s  %-8s  run: %s".format("", "", it) } ?: line
    }
}

/** Where `status` shows each observability backend. */
data class ObservabilityAddresses(
    val grafana: StatusAddress,
    val mimir: StatusAddress,
    val loki: StatusAddress,
    val tempo: StatusAddress,
    val pyroscope: StatusAddress,
)

/**
 * The addresses `status` (the command and the server's status cache) prints for cluster Services
 * and kit endpoints, chosen by the cluster's recorded [Exposure].
 *
 * On Socks and Direct a Service runs on host networking, so it is shown at its node's private
 * address, which the tunnel or the routed network reaches. A port-forward cannot outlive `status`,
 * so on a port-forward cluster each one is shown by its Service DNS name with the
 * `bin/kubectl port-forward` command (through the workspace wrapper) the operator runs to reach it.
 *
 * A kit endpoint names its NodePort, which neither a Service DNS name nor a Service port-forward
 * takes, so an installed kit's NodePorts are mapped to the Service ports its rendered manifests
 * declare ([KitServicePorts]).
 *
 * @param state the cluster state
 */
class StatusAddresses(
    private val state: ClusterState,
) {
    private val log = KotlinLogging.logger {}
    private val forwarded = state.exposure == Exposure.PortForward

    /** The address of one cluster [target] Service, as an HTTP URL. */
    fun service(target: ServiceTarget): StatusAddress =
        if (forwarded) {
            forwardedAddress(target.namespace, target.service, target.servicePort, target.servicePort) { host, port ->
                "http://$host:$port"
            }
        } else {
            StatusAddress("http://${state.nodeAddressFor(target)}:${target.nodePort}")
        }

    /** The observability backends, Mimir under the path its Prometheus API is served on. */
    fun observability(): ObservabilityAddresses =
        ObservabilityAddresses(
            grafana = service(ClusterServices.GRAFANA),
            mimir = service(ClusterServices.MIMIR).let { it.copy(address = it.address + MIMIR_PROMETHEUS_PATH) },
            loki = service(ClusterServices.LOKI),
            tempo = service(ClusterServices.TEMPO),
            pyroscope = service(ClusterServices.PYROSCOPE),
        )

    /**
     * The addresses of a kit [endpoint]: one per host of its node type, or its Service on a
     * port-forward cluster, at the Service port [servicePort] gives for the endpoint's NodePort.
     */
    fun kitEndpoint(
        kitName: String,
        runtime: KitRuntime?,
        endpoint: KitEndpoint,
        servicePort: (KitService, Int) -> Int = { _, port -> port },
    ): List<StatusAddress> {
        if (!forwarded) {
            return KitEndpointAddresses.resolve(listOf(endpoint), state.hosts).map { StatusAddress(it.address) }
        }
        val service = KitService.of(kitName, runtime)
        val port = servicePort(service, endpoint.port)
        return listOf(forwardedAddress(service.namespace, service.name, endpoint.port, port) { host, p -> endpoint.formatUrl(host, p) })
    }

    /**
     * Every endpoint the `kit.yaml` of the installed kit in [kitDir] declares, with its addresses.
     * Fails, and logs why, when the descriptor cannot be read, so a caller can tell an unreadable
     * descriptor from a kit that declares no endpoints.
     */
    fun installedKit(kitDir: File): Result<List<ShownEndpoint>> =
        runCatching {
            installConfigYaml.decodeFromString(KitConfig.serializer(), File(kitDir, Constants.Kit.CONFIG_FILE).readText())
        }.onFailure { e ->
            log.warn(e) { "Cannot read ${Constants.Kit.CONFIG_FILE} of kit '${kitDir.name}'; its endpoints are not shown" }
        }.map { config ->
            val ports = if (forwarded) KitServicePorts.read(kitDir) else null
            config.endpoints.flatMap { endpoint ->
                kitEndpoint(kitDir.name, config.runtime, endpoint) { service, port -> ports?.servicePort(service, port) ?: port }
                    .map { ShownEndpoint(endpoint, it) }
            }
        }

    private fun forwardedAddress(
        namespace: String,
        service: String,
        localPort: Int,
        remotePort: Int,
        format: (String, Int) -> String,
    ): StatusAddress {
        val ports = if (localPort == remotePort) "$remotePort" else "$localPort:$remotePort"
        return StatusAddress(
            address = format("$service.$namespace.svc.cluster.local", remotePort),
            hint = "bin/kubectl port-forward -n $namespace svc/$service $ports",
        )
    }

    private companion object {
        const val MIMIR_PROMETHEUS_PATH = "/prometheus"
    }
}
