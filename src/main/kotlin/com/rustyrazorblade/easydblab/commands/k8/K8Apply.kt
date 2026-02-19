package com.rustyrazorblade.easydblab.commands.k8

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaUpload
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.displayObservabilityAccess
import com.rustyrazorblade.easydblab.services.K8sService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Apply observability stack to the K8s cluster.
 *
 * Delegates to GrafanaUpload for deploying all observability resources,
 * then waits for pods to be ready and displays access information.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "apply",
    description = ["Apply observability stack to K8s cluster"],
)
class K8Apply : PicoBaseCommand() {
    private val k8sService: K8sService by inject()
    private val grafanaUpload: GrafanaUpload by inject()

    @Suppress("MagicNumber")
    @Option(
        names = ["--timeout"],
        description = ["Timeout in seconds to wait for pods to be ready (default: 120)"],
    )
    var timeoutSeconds: Int = 120

    @Option(
        names = ["--skip-wait"],
        description = ["Skip waiting for pods to be ready"],
    )
    var skipWait: Boolean = false

    override fun execute() {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()

        // Deploy the full observability stack
        grafanaUpload.execute()

        // Wait for pods to be ready
        if (!skipWait) {
            k8sService
                .waitForPodsReady(controlNode, timeoutSeconds)
                .getOrElse { exception ->
                    outputHandler.handleError("Warning: Pods may not be ready: ${exception.message}")
                    outputHandler.handleMessage("You can check status with: kubectl get pods")
                }
        }

        // Display access information
        outputHandler.handleMessage("")
        outputHandler.handleMessage("Observability stack deployed successfully!")
        outputHandler.displayObservabilityAccess(controlNode.privateIp)
    }
}
