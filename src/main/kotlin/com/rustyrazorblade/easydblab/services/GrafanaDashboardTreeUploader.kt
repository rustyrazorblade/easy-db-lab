package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import kotlin.io.path.createTempDirectory

/**
 * Puts the core dashboard tree on the control node where Grafana's file provider reads it.
 *
 * Dashboards are files on the Grafana hostPath, not K8s objects, so getting them to the cluster
 * is a copy: lay the tree out locally, ship it over SSH, swap it into place. Grafana's provider
 * polls the path, so a fresh copy is picked up without anything else changing.
 */
interface GrafanaDashboardTreeUploader {
    /**
     * Replaces the dashboard tree on [controlHost] with the one on this CLI's classpath.
     *
     * @param controlHost The control node running Grafana
     */
    fun upload(controlHost: ClusterHost)
}

/**
 * Default [GrafanaDashboardTreeUploader]: writes the tree to a local temp directory and hands it
 * to [RemoteOperationsService.replaceDirectory], which swaps it into
 * [GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH] as the Grafana user without the provider
 * ever polling an empty or half-copied directory. A dashboard deleted from the repo still
 * disappears from Grafana because the whole tree is replaced.
 *
 * @property writer Lays the catalog out on the local filesystem
 * @property remoteOps SSH transport to the control node
 * @property eventBus Reports the upload as a lifecycle step
 */
class DefaultGrafanaDashboardTreeUploader(
    private val writer: GrafanaDashboardTreeWriter,
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : GrafanaDashboardTreeUploader {
    override fun upload(controlHost: ClusterHost) {
        val hostPath = GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH
        val localTree = createTempDirectory(LOCAL_TEMP_PREFIX)
        try {
            val count = writer.writeTo(localTree, pyroscopeIngestBaseUrl(controlHost.privateIp))
            eventBus.emit(Event.Grafana.DashboardTreeUploading(count, hostPath))
            remoteOps.replaceDirectory(controlHost.toHost(), localTree.toFile(), hostPath, GRAFANA_OWNER)
            eventBus.emit(Event.Grafana.DashboardTreeUploaded(count, hostPath))
        } finally {
            localTree.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val LOCAL_TEMP_PREFIX = "easy-db-lab-grafana-dashboards"

        /** `chown` spec for the uploaded tree: the Grafana container's uid and gid. */
        const val GRAFANA_OWNER = "${GrafanaManifestBuilder.GRAFANA_UID}:${GrafanaManifestBuilder.GRAFANA_UID}"
    }
}
