package com.rustyrazorblade.easydblab.commands.opensearch

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.aws.DomainState
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import software.amazon.awssdk.services.opensearch.model.ResourceNotFoundException

/**
 * Check the status of the OpenSearch domain.
 *
 * Displays current domain state, endpoints, and configuration.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "status",
    description = ["Check OpenSearch domain status"],
)
class OpenSearchStatus : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val openSearchService: OpenSearchService by inject()

    @Option(names = ["--endpoint"], description = ["Output only the endpoint URL (for scripting)"])
    var endpointOnly: Boolean = false

    override fun execute() {
        val domainState = clusterState.openSearchDomain
        if (domainState == null) {
            if (!endpointOnly) {
                outputHandler.handleMessage("No OpenSearch domain configured for this cluster.")
                outputHandler.handleMessage("Use 'opensearch start' to create one.")
            }
            return
        }

        log.info { "Checking OpenSearch domain status: ${domainState.domainName}" }

        try {
            val result = openSearchService.describeDomain(domainState.domainName)

            // Handle --endpoint flag: output only the endpoint URL
            if (endpointOnly) {
                if (result.endpoint != null) {
                    outputHandler.handleMessage(result.endpoint)
                }
                return
            }

            outputHandler.handleMessage("")
            outputHandler.handleMessage("OpenSearch Domain Status")
            outputHandler.handleMessage("========================")
            outputHandler.handleMessage("Domain Name: ${result.domainName}")
            outputHandler.handleMessage("Domain ID:   ${result.domainId}")

            // Show user-friendly status based on actual readiness
            val isReady = result.endpoint != null
            val statusDisplay =
                when {
                    isReady -> "Ready"
                    result.state == DomainState.PROCESSING -> "Creating..."
                    result.state == DomainState.DELETED -> "Deleted"
                    else -> "Creating..." // ACTIVE but no endpoint yet
                }
            outputHandler.handleMessage("Status:      $statusDisplay")
            outputHandler.handleMessage("")

            if (isReady) {
                outputHandler.handleMessage("Endpoints:")
                outputHandler.handleMessage("  REST API:   https://${result.endpoint}")
                outputHandler.handleMessage("  Dashboards: ${result.dashboardsEndpoint}")
            } else {
                outputHandler.handleMessage("Endpoint not yet available.")
                outputHandler.handleMessage("Domain creation typically takes 10-30 minutes.")
            }
            outputHandler.handleMessage("")

            // Update local state if it differs
            if (result.endpoint != domainState.endpoint || result.state.name != domainState.state) {
                clusterState.updateOpenSearchDomain(
                    domainState.copy(
                        endpoint = result.endpoint,
                        dashboardsEndpoint = result.dashboardsEndpoint,
                        state = result.state.name,
                    ),
                )
                clusterStateManager.save(clusterState)
            }
        } catch (e: ResourceNotFoundException) {
            // Domain was deleted - clear local state
            log.info { "OpenSearch domain ${domainState.domainName} no longer exists" }
            if (!endpointOnly) {
                outputHandler.handleMessage("OpenSearch domain '${domainState.domainName}' no longer exists.")
                outputHandler.handleMessage("Clearing local state.")
            }
            clusterState.updateOpenSearchDomain(null)
            clusterStateManager.save(clusterState)
        } catch (e: Exception) {
            // Transient error - show cached state with warning
            log.warn { "Failed to get domain status: ${e.message}" }
            if (!endpointOnly) {
                outputHandler.handleMessage("Warning: Could not fetch current domain status (${e.message})")
                outputHandler.handleMessage("")
                outputHandler.handleMessage("Cached state (may be stale):")
                outputHandler.handleMessage("  Domain:   ${domainState.domainName}")
                outputHandler.handleMessage("  State:    ${domainState.state}")
                if (domainState.endpoint != null) {
                    outputHandler.handleMessage("  Endpoint: ${domainState.endpoint}")
                }
            }
        }
    }
}
