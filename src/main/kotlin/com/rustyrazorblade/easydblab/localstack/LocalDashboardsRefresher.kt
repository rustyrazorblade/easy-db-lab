package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import java.io.File

/**
 * `observability local dashboards <dir> [--from <dir>]`: replaces the dashboards of a running local
 * stack and has its Grafana reload them, with no restart.
 *
 * It reads the stack's manifest for Grafana's port and checks that Grafana answers there; if it does
 * not, it emits [Event.LocalStack.GrafanaNotRunning] and changes nothing. It never calls docker, so no
 * container is recreated and no data is touched; only `dashboards/` changes.
 *
 * @property grafana the stack's Grafana, for the health check.
 * @property dashboards renders, installs and reloads the tree.
 * @property eventBus reports the install or the refusal.
 */
class LocalDashboardsRefresher(
    private val grafana: LocalGrafana,
    private val dashboards: LocalStackDashboards,
    private val eventBus: EventBus,
) {
    /** Installs the dashboards under [from] (the packaged ones when null) in the running stack in [directory]. */
    fun refresh(
        directory: File,
        from: File?,
    ) {
        val manifestFile = File(directory, Constants.LocalStack.MANIFEST_FILE)
        check(manifestFile.isFile) {
            "$directory holds no local stack (no ${Constants.LocalStack.MANIFEST_FILE}); run 'observability local create $directory' first"
        }
        val manifest = LocalStackManifest.read(manifestFile)
        if (!grafana.isRunning(manifest.grafanaPort)) {
            eventBus.emit(Event.LocalStack.GrafanaNotRunning(directory.path, grafanaUrl(manifest.grafanaPort)))
            throw CommandFailedException("The local stack's Grafana in $directory is not running")
        }
        eventBus.emit(dashboards.install(directory, manifest, from?.let(::DirectoryDashboardTree), reload = true))
    }
}
