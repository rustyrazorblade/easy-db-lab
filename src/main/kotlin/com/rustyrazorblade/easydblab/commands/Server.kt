package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.server.StatusCache
import com.rustyrazorblade.easydblab.server.StatusServer
import io.github.oshai.kotlinlogging.KotlinLogging
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Starts the REST status server: cluster status at `/status` and `/stress/status`, API docs at
 * `/swagger`.  It runs until it is stopped.
 *
 * Carries [RequiresProxy]: the [StatusCache] refresh calls K3s and Kubernetes through Fabric8
 * (`K8sClientProvider`) every cycle, inside this command's own execution, so the tunnel must be
 * established before [execute] starts the server.
 */
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "server",
    description = ["Start the REST status server (cluster status at /status, API docs at /swagger)"],
)
class Server : PicoBaseCommand() {
    @Option(
        names = ["--port", "-p"],
        description = ["Server port (0 = any free port)"],
    )
    var port: Int = 0

    @Option(
        names = ["--bind", "-b"],
        description = ["Address to bind the server to (default: 127.0.0.1)"],
    )
    var bind: String = "127.0.0.1"

    @Option(
        names = ["--refresh", "-r"],
        description = ["Status cache refresh interval in seconds (default: 30)"],
    )
    var refreshInterval: Long = Constants.Time.DEFAULT_STATUS_REFRESH_SECONDS

    @Option(
        names = ["--auto-shutdown"],
        description = ["Shut down the server if the cluster VPC is removed"],
    )
    var autoShutdown: Boolean = false

    override fun execute() {
        log.info { "Starting easy-db-lab status server..." }
        val vpcName =
            if (autoShutdown && clusterStateManager.exists()) {
                "easy-db-lab-${clusterStateManager.load().name}"
            } else {
                null
            }
        val statusCache = StatusCache(refreshInterval, autoShutdown, vpcName)
        statusCache.start()
        StatusServer(statusCache).start(port, bind).use {
            Thread.currentThread().join()
        }
        log.info { "Server stopped." }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
