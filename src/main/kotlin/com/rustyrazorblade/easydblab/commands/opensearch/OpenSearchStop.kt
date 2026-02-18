package com.rustyrazorblade.easydblab.commands.opensearch

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Delete the OpenSearch domain.
 *
 * This command deletes the AWS-managed OpenSearch domain associated with
 * the current cluster. The deletion is permanent and all data will be lost.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "stop",
    description = ["Delete the OpenSearch domain"],
)
class OpenSearchStop : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val openSearchService: OpenSearchService by inject()

    @Option(
        names = ["--force"],
        description = ["Force deletion without confirmation"],
    )
    var force: Boolean = false

    override fun execute() {
        val domainState = clusterState.openSearchDomain
        if (domainState == null) {
            outputHandler.handleMessage("No OpenSearch domain configured for this cluster.")
            return
        }

        if (!force) {
            outputHandler.handleMessage("This will delete the OpenSearch domain and all its data.")
            outputHandler.handleMessage("Domain: ${domainState.domainName}")
            outputHandler.handleMessage("Use --force to confirm deletion.")
            return
        }

        log.info { "Deleting OpenSearch domain: ${domainState.domainName}" }

        openSearchService.deleteDomain(domainState.domainName)

        // Clear the domain from cluster state
        clusterState.updateOpenSearchDomain(null)
        clusterStateManager.save(clusterState)

        outputHandler.handleMessage("OpenSearch domain deletion initiated: ${domainState.domainName}")
        outputHandler.handleMessage("Note: Domain deletion may take several minutes to complete.")
    }
}
