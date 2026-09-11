package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.ObservabilityStackService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Build and apply the full observability stack to the K8s cluster.
 *
 * This command is a thin wrapper over [ObservabilityStackService], which owns the deploy
 * orchestration shared with cluster bring-up (`up`). The stack it deploys includes the OTel
 * collector, VictoriaMetrics + VictoriaLogs, Tempo, Beyla, ebpf_exporter, the Pyroscope server and
 * eBPF agent, Grafana with dashboards, the docker registry, and the S3 manager.
 *
 * A telemetry-redirect cluster has no local Grafana or backends to reconfigure — its telemetry
 * lives on an external stack — so the command refuses on such a cluster rather than silently doing
 * nothing. Bring-up reaches [ObservabilityStackService] directly and still deploys the collectors
 * in redirect mode; this command is the operator-facing local-stack reconfigure path only.
 */
@McpCommand
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "update-config",
    description = ["Build and apply the full observability stack to K8s cluster"],
)
class GrafanaUpdateConfig : PicoBaseCommand() {
    private val observabilityStackService: ObservabilityStackService by inject()

    override fun execute() {
        requireLocalTelemetryStack("grafana update-config")

        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()

        observabilityStackService.deploy(controlNode, telemetryRedirect = null).getOrElse { exception ->
            error("Failed to deploy observability stack: ${exception.message}")
        }
    }
}
