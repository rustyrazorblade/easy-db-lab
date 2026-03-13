package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Service for managing cassandra-sidecar lifecycle as a K8s DaemonSet.
 *
 * The sidecar runs as ghcr.io/apache/cassandra-sidecar:latest on all db nodes
 * via k3s. Operations are cluster-level rather than per-host.
 */
interface SidecarService {
    /**
     * Deploys the cassandra-sidecar DaemonSet to the cluster.
     * This is idempotent — safe to call when the DaemonSet already exists.
     */
    fun deploy(controlHost: ClusterHost): Result<Unit>

    /**
     * Performs a rolling restart of the cassandra-sidecar DaemonSet.
     */
    fun rolloutRestart(controlHost: ClusterHost): Result<Unit>

    /**
     * Removes the cassandra-sidecar DaemonSet from the cluster.
     */
    fun remove(controlHost: ClusterHost): Result<Unit>
}

/**
 * Default implementation of SidecarService backed by K8s.
 *
 * @property k8sService Used for applying and managing K8s resources
 * @property sidecarManifestBuilder Builds the cassandra-sidecar DaemonSet
 * @property eventBus Event bus for emitting domain events
 */
class DefaultSidecarService(
    private val k8sService: K8sService,
    private val sidecarManifestBuilder: SidecarManifestBuilder,
    private val eventBus: EventBus,
) : SidecarService {
    companion object {
        private const val DAEMONSET_NAME = "cassandra-sidecar"
        private const val NAMESPACE = "default"
    }

    override fun deploy(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.info { "Deploying cassandra-sidecar DaemonSet" }
            for (resource in sidecarManifestBuilder.buildAllResources()) {
                k8sService.applyResource(controlHost, resource).getOrThrow()
            }
            log.info { "cassandra-sidecar DaemonSet deployed" }
        }

    override fun rolloutRestart(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.info { "Rolling restart of cassandra-sidecar DaemonSet" }
            k8sService.rolloutRestartDaemonSet(controlHost, DAEMONSET_NAME, NAMESPACE).getOrThrow()
            log.info { "cassandra-sidecar DaemonSet rollout restart initiated" }
        }

    override fun remove(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.info { "Removing cassandra-sidecar DaemonSet" }
            k8sService
                .deleteResourcesByLabel(
                    controlHost,
                    NAMESPACE,
                    "app.kubernetes.io/name",
                    listOf(DAEMONSET_NAME),
                ).getOrThrow()
            log.info { "cassandra-sidecar DaemonSet removed" }
        }
}
