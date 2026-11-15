package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService

/**
 * The host directories the backends and Grafana keep their state in when it lives on the control
 * node ([com.rustyrazorblade.easydblab.configuration.BackendStorage.HostPath]), created over SSH and
 * owned by the user each image runs as. Kept apart from the stack deploy so the deploy only decides
 * whether the directories are needed, and this class alone knows their paths and owners.
 */
class OnNodeStateDirectories(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) {
    /**
     * The Pyroscope server, Tempo, Mimir and Loki directories. Tempo keeps both write-ahead logs, so
     * spans received but not yet cut into a block survive a pod restart; Mimir keeps its TSDB (head,
     * WAL and local blocks); Loki its WAL, index head, index cache and compactor directory.
     */
    fun prepareBackends(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.PyroscopeDirectoryPreparing)
        owned(controlNode, "/mnt/db1/pyroscope", PyroscopeManifestBuilder.PYROSCOPE_UID)

        eventBus.emit(Event.Grafana.TempoDirectoryPreparing)
        owned(controlNode, TempoManifestBuilder.DATA_HOST_PATH, TempoManifestBuilder.TEMPO_UID)

        // The Mimir image runs as root, so only the directory is needed.
        eventBus.emit(Event.Grafana.MimirDirectoryPreparing)
        remoteOps.executeRemotely(controlNode.toHost(), "sudo mkdir -p ${MimirManifestBuilder.DATA_HOST_PATH}")

        eventBus.emit(Event.Grafana.LokiDirectoryPreparing)
        owned(controlNode, LokiManifestBuilder.DATA_HOST_PATH, LokiManifestBuilder.LOKI_UID)
    }

    /** Grafana's data directory, which also holds the dashboard tree. */
    fun prepareGrafana(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.GrafanaDirectoryPreparing)
        owned(controlNode, GrafanaManifestBuilder.GRAFANA_DATA_PATH, GrafanaManifestBuilder.GRAFANA_UID.toLong())
    }

    private fun owned(
        controlNode: ClusterHost,
        path: String,
        uid: Long,
    ) {
        remoteOps.executeRemotely(controlNode.toHost(), "sudo mkdir -p $path && sudo chown -R $uid:$uid $path")
    }
}
