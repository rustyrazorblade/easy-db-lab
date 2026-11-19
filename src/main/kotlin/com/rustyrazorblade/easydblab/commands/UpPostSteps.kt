package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.services.CiliumInstallAnnotator
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.KitStorageResolver
import com.rustyrazorblade.easydblab.services.NodeSetupService
import com.rustyrazorblade.easydblab.services.ObservabilityStackService

/**
 * The cluster-wide steps `up` runs once, after every node's pipeline has ended (design D2): the
 * recorded kit StorageClass check, the observability stack, and on a local-stack cluster the Cilium
 * install annotations, which need Grafana to exist.
 *
 * Bring-up drives the observability stack service directly rather than `grafana update-config`:
 * on a redirect cluster that command refuses, but bring-up must still deploy the collectors
 * pointed at the external stack.
 */
class UpPostSteps(
    private val kitStorage: KitStorageResolver,
    private val observability: ObservabilityStackService,
    private val annotator: CiliumInstallAnnotator,
    private val eventBus: EventBus,
) {
    /**
     * Runs the steps; [kitStorageClass] is the class `init` recorded with `--kit-storage-class`, if
     * any. Every step finds the control node and the cluster's API from the cluster state.
     */
    fun run(
        localTelemetry: Boolean,
        kitStorageClass: String?,
    ) {
        // Kits on local volumes get their own class at install (`kit-storage`). A recorded kit class is
        // checked here, before anything claims from it.
        kitStorageClass?.let { kitStorage.resolve(it) }
        observability
            .deploy()
            .getOrElse { error("Observability stack deployment failed during provisioning: ${it.message}") }
        if (localTelemetry) postCiliumInstallAnnotations()
    }

    /** A failure here is reported, not fatal: the cluster is up and only the dashboard marker is missing. */
    private fun postCiliumInstallAnnotations() {
        annotator
            .post()
            .onSuccess { posted ->
                posted.forEach { (request, response) ->
                    eventBus.emit(Event.Grafana.AnnotationCreated(response.id, request.text, request.tags, request.time))
                }
            }.onFailure { eventBus.emit(Event.Grafana.AnnotationFailed(it.messageOrString())) }
    }
}

/**
 * Pushes the two files that name db0 again, after db0 was replaced (design D2): every node's
 * `environment.sh` login profile, and the app nodes' `stress.env`. They were written with the
 * first db0's private IP; this is the only case where one node's setup depends on another.
 */
class EnvironmentRepush(
    private val nodeSetup: NodeSetupService,
    private val hostOperations: HostOperationsService,
) {
    /** Pushes both files to every node in [hosts], db0's private IP from the same map. */
    fun push(hosts: Map<ServerType, List<ClusterHost>>) {
        val databaseHost = hosts[ServerType.Cassandra]?.firstOrNull()?.privateIp.orEmpty()
        val everyNode = listOf(ServerType.Control, ServerType.Cassandra, ServerType.Stress).flatMap { hosts[it].orEmpty() }
        hostOperations.withHosts(everyNode, parallel = true) { nodeSetup.installEnvironment(it.toHost()) }
        hostOperations.withHosts(hosts[ServerType.Stress].orEmpty(), parallel = true) { app ->
            val host = app.toHost()
            nodeSetup.installStressEnv(host, databaseHost, nodeSetup.readRegion(host))
        }
    }
}
