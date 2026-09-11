package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationBackupService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Backs up the cluster's Grafana annotations to an account-level S3 location.
 *
 * The annotations are the A/B config-change markers worth keeping after the ephemeral cluster is
 * torn down, so the artifact lands outside the per-cluster prefix that teardown expires. The command
 * reaches the Grafana HTTP API over the proxied client, so it carries `@RequiresProxy`.
 *
 * If no S3 bucket is configured, the backup fails fast with the standard "run up first" message.
 */
@McpCommand
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "backup",
    description = ["Back up Grafana annotations to an account-level S3 location"],
    mixinStandardHelpOptions = true,
)
class GrafanaBackup : PicoBaseCommand() {
    private val annotationBackupService: GrafanaAnnotationBackupService by inject()

    override fun execute() {
        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull()
                ?: error("No control node found in cluster state.")

        // getOrThrow so a missing bucket or an unreachable Grafana exits non-zero; the backup service
        // emits the start/complete events and reports the resulting S3 URI on success.
        annotationBackupService.backup(controlHost, clusterState).getOrThrow()
    }
}
