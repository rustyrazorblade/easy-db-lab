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
                eventBus.emit(Event.OpenSearch.NotConfigured)
                eventBus.emit(Event.OpenSearch.UseStartHint)
            }
            return
        }

        log.info { "Checking OpenSearch domain status: ${domainState.domainName}" }

        try {
            val result = openSearchService.describeDomain(domainState.domainName)

            if (endpointOnly) {
                if (result.endpoint != null) {
                    eventBus.emit(Event.OpenSearch.EndpointOnly(result.endpoint))
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
        val statusDisplay =
            when {
                result.endpoint != null -> "Ready"
                result.state == DomainState.PROCESSING -> "Creating..."
                result.state == DomainState.DELETED -> "Deleted"
                else -> "Creating..."
            }

        eventBus.emit(
            Event.OpenSearch.DomainStatus(
                domainName = result.domainName,
                domainId = result.domainId,
                state = statusDisplay,
                endpoint = result.endpoint,
                dashboardsEndpoint = result.dashboardsEndpoint,
            ),
        )
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
            eventBus.emit(Event.OpenSearch.DomainNotExists(domainState.domainName))
            eventBus.emit(Event.OpenSearch.ClearingState)
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
            eventBus.emit(Event.OpenSearch.FetchWarning(e.message ?: "unknown error"))
            eventBus.emit(
                Event.OpenSearch.CachedState(
                    domainName = domainState.domainName,
                    state = domainState.state,
                    endpoint = domainState.endpoint,
                ),
            )
        }
    }
}
