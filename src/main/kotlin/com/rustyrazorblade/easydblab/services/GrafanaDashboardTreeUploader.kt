package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
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
 * Default [GrafanaDashboardTreeUploader]: local temp tree, SFTP to a staging directory under the
 * SSH user's home, then one remote command to swap it into
 * [GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH] owned by the Grafana user.
 *
 * The swap removes the old tree first so a dashboard deleted from the repo disappears from
 * Grafana too; `mv` consumes the staging directory, so nothing is left behind on success.
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
        val host = controlHost.toHost()
        val hostPath = GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH
        val pyroscopeUrl = "http://${controlHost.privateIp}:${Constants.K8s.PYROSCOPE_PORT}"

        val localTree = createTempDirectory(LOCAL_TEMP_PREFIX)
        try {
            writer.writeTo(localTree, pyroscopeUrl)
            val count = localTree.toFile().walkTopDown().count { it.isFile }
            eventBus.emit(Event.Grafana.DashboardTreeUploading(count, hostPath))

            val staging =
                remoteOps
                    .executeRemotely(host, "mktemp -d \"\$HOME/$STAGING_TEMPLATE\"", output = false)
                    .text
                    .trim()
            remoteOps.uploadDirectory(host, localTree.toFile(), staging)
            remoteOps.executeRemotely(
                host,
                "sudo rm -rf $hostPath && sudo mv $staging $hostPath && " +
                    "sudo chown -R ${GrafanaManifestBuilder.GRAFANA_UID}:${GrafanaManifestBuilder.GRAFANA_UID} $hostPath",
                output = false,
            )
            eventBus.emit(Event.Grafana.DashboardTreeUploaded(count, hostPath))
        } finally {
            localTree.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val LOCAL_TEMP_PREFIX = "easy-db-lab-grafana-dashboards"

        /** `mktemp` template, resolved under `$HOME` on the control node. */
        const val STAGING_TEMPLATE = "easy-db-lab-grafana-dashboards.XXXXXX"
    }
}
