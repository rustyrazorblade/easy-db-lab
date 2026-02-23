package com.rustyrazorblade.easydblab.commands.opensearch

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.aws.DomainState
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainResult
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

    @Suppress("TooGenericExceptionCaught")
    override fun execute() {
        val domainState = clusterState.openSearchDomain
        if (domainState == null) {
            if (!endpointOnly) {
                eventBus.emit(Event.Message("No OpenSearch domain configured for this cluster."))
                eventBus.emit(Event.Message("Use 'opensearch start' to create one."))
            }
            return
        }

        log.info { "Checking OpenSearch domain status: ${domainState.domainName}" }

        try {
            val result = openSearchService.describeDomain(domainState.domainName)

            if (endpointOnly) {
                if (result.endpoint != null) {
                    eventBus.emit(Event.Message(result.endpoint))
                }
                return
            }

            displayDomainStatus(result)
            updateLocalStateIfChanged(domainState, result)
        } catch (e: ResourceNotFoundException) {
            handleDomainNotFound(domainState)
        } catch (e: Exception) {
            handleStatusError(domainState, e)
        }
    }

    private fun displayDomainStatus(result: OpenSearchDomainResult) {
        val isReady = result.endpoint != null
        val statusDisplay =
            when {
                isReady -> "Ready"
                result.state == DomainState.PROCESSING -> "Creating..."
                result.state == DomainState.DELETED -> "Deleted"
                else -> "Creating..."
            }

        eventBus.emit(
            Event.Message(
                """
            |
            |OpenSearch Domain Status
            |========================
            |Domain Name: ${result.domainName}
            |Domain ID:   ${result.domainId}
            |Status:      $statusDisplay
                """.trimMargin(),
            ),
        )
        eventBus.emit(Event.Message(""))

        if (isReady) {
            eventBus.emit(Event.Message("Endpoints:"))
            eventBus.emit(Event.Message("  REST API:   https://${result.endpoint}"))
            eventBus.emit(Event.Message("  Dashboards: ${result.dashboardsEndpoint}"))
        } else {
            eventBus.emit(Event.Message("Endpoint not yet available."))
            eventBus.emit(Event.Message("Domain creation typically takes 10-30 minutes."))
        }
        eventBus.emit(Event.Message(""))
    }

    private fun updateLocalStateIfChanged(
        domainState: OpenSearchClusterState,
        result: OpenSearchDomainResult,
    ) {
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
    }

    private fun handleDomainNotFound(domainState: OpenSearchClusterState) {
        log.info { "OpenSearch domain ${domainState.domainName} no longer exists" }
        if (!endpointOnly) {
            eventBus.emit(Event.Message("OpenSearch domain '${domainState.domainName}' no longer exists."))
            eventBus.emit(Event.Message("Clearing local state."))
        }
        clusterState.updateOpenSearchDomain(null)
        clusterStateManager.save(clusterState)
    }

    private fun handleStatusError(
        domainState: OpenSearchClusterState,
        e: Exception,
    ) {
        log.warn { "Failed to get domain status: ${e.message}" }
        if (!endpointOnly) {
            eventBus.emit(Event.Message("Warning: Could not fetch current domain status (${e.message})"))
            eventBus.emit(Event.Message(""))
            eventBus.emit(Event.Message("Cached state (may be stale):"))
            eventBus.emit(Event.Message("  Domain:   ${domainState.domainName}"))
            eventBus.emit(Event.Message("  State:    ${domainState.state}"))
            if (domainState.endpoint != null) {
                eventBus.emit(Event.Message("  Endpoint: ${domainState.endpoint}"))
            }
        }
    }
}
