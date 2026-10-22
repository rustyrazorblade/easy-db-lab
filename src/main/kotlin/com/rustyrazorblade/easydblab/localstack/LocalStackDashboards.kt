package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.Event
import java.io.File

/**
 * A local stack's dashboard tree: rendered for the stack its manifest records, installed in
 * `dashboards/current` ([DashboardTreeInstaller]), and, when asked, reloaded by its running Grafana.
 * `local create` and `local dashboards` both install through here, so both apply the same pass.
 *
 * The install-time pass gets the live local stack's context: no current cluster and no current
 * experiment (every open shows the newest experiment), the manifest's home tenant and tenants, and
 * the documents web server on its published port.
 *
 * @property grafana the stack's Grafana, for the reload.
 * @property packaged the dashboards packaged in the distribution, used when no directory is given.
 */
class LocalStackDashboards(
    private val grafana: LocalGrafana,
    private val packaged: DashboardTreeSource,
) {
    /** The install-time context of the stack recorded in [manifest]. */
    fun context(manifest: LocalStackManifest): DashboardInstallContext =
        DashboardInstallContext(
            cluster = null,
            experiment = null,
            tenants = TenantSet.of(manifest.homeTenant, manifest.tenants),
            documentsUrl = "http://${Constants.LocalStack.LOOPBACK}:${manifest.documentsPort}",
        )

    /**
     * Installs the dashboards of [source] (the packaged ones when null) in the stack in [directory],
     * then has its Grafana reload them when [reload] is set. The tree is rendered before anything on
     * disk changes, so a bad file changes nothing.
     */
    fun install(
        directory: File,
        manifest: LocalStackManifest,
        source: DashboardTreeSource?,
        reload: Boolean,
    ): Event.LocalStack.DashboardsInstalled {
        val tree = (source ?: packaged).render(context(manifest))
        DashboardTreeInstaller.install(directory, tree)
        if (reload) grafana.reloadDashboards(manifest.grafanaPort)
        return Event.LocalStack.DashboardsInstalled(
            directory = directory.path,
            dashboards = tree.size,
            from = source?.origin,
            reloaded = reload,
        )
    }
}
