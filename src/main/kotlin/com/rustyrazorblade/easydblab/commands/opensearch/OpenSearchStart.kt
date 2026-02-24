package com.rustyrazorblade.easydblab.commands.opensearch

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainConfig
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Create an AWS OpenSearch domain.
 *
 * This command creates an AWS-managed OpenSearch domain in the cluster's VPC.
 * The domain will be accessible from all nodes in the cluster.
 *
 * OpenSearch domains typically take 10-30 minutes to create. The command will
 * wait for the domain to become active unless --skip-wait is specified.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "start",
    description = ["Create an AWS OpenSearch domain"],
)
class OpenSearchStart : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val openSearchService: OpenSearchService by inject()
    private val user: User by inject()

    @Option(
        names = ["--instance-type"],
        description = ["OpenSearch instance type (e.g., t3.small.search, r5.large.search)"],
    )
    var instanceType: String = Constants.OpenSearch.DEFAULT_INSTANCE_TYPE

    @Option(
        names = ["--instance-count"],
        description = ["Number of OpenSearch data nodes"],
    )
    var instanceCount: Int = Constants.OpenSearch.DEFAULT_INSTANCE_COUNT

    @Option(
        names = ["--version"],
        description = ["OpenSearch engine version (e.g., 2.11, 2.9)"],
    )
    var version: String = Constants.OpenSearch.DEFAULT_VERSION

    @Option(
        names = ["--ebs-size"],
        description = ["EBS volume size in GB per node"],
    )
    var ebsSize: Int = Constants.OpenSearch.DEFAULT_EBS_SIZE_GB

    @Option(
        names = ["--wait"],
        description = ["Wait for domain to become active (can take 10-30 minutes)"],
    )
    var wait: Boolean = false

    override fun execute() {
        // Validate infrastructure is ready
        val infrastructure =
            clusterState.infrastructure
                ?: error("Infrastructure not ready. Please run 'up' first.")

        val subnetId =
            infrastructure.subnetIds.firstOrNull()
                ?: error("No subnet found in infrastructure. Please run 'up' first.")

        val securityGroupId =
            infrastructure.securityGroupId
                ?: error("No security group found in infrastructure. Please run 'up' first.")

        // Generate domain name from cluster name (must be 3-28 lowercase chars)
        val domainName = generateDomainName(clusterState.name)

        log.info { "Creating OpenSearch domain: $domainName" }

        // Ensure the OpenSearch service-linked role exists (required for VPC access)
        eventBus.emit(Event.OpenSearch.ServiceLinkedRoleEnsuring)
        openSearchService.ensureServiceLinkedRole()

        val config =
            OpenSearchDomainConfig(
                domainName = domainName,
                instanceType = instanceType,
                instanceCount = instanceCount,
                ebsVolumeSize = ebsSize,
                engineVersion = "OpenSearch_$version",
                subnetId = subnetId,
                securityGroupIds = listOf(securityGroupId),
                accountId = openSearchService.getAccountId(),
                region = user.region,
                tags =
                    mapOf(
                        Constants.Vpc.TAG_KEY to Constants.Vpc.TAG_VALUE,
                        "ClusterId" to clusterState.clusterId,
                    ),
            )

        val result = openSearchService.createDomain(config)

        // Update cluster state with domain info
        clusterState.updateOpenSearchDomain(
            OpenSearchClusterState(
                domainName = result.domainName,
                domainId = result.domainId,
                endpoint = result.endpoint,
                dashboardsEndpoint = result.dashboardsEndpoint,
                state = result.state.name,
            ),
        )
        clusterStateManager.save(clusterState)

        if (wait) {
            eventBus.emit(Event.OpenSearch.Waiting)
            val activeResult = openSearchService.waitForDomainActive(domainName)

            // Update state with endpoint info
            clusterState.updateOpenSearchDomain(
                OpenSearchClusterState(
                    domainName = activeResult.domainName,
                    domainId = activeResult.domainId,
                    endpoint = activeResult.endpoint,
                    dashboardsEndpoint = activeResult.dashboardsEndpoint,
                    state = activeResult.state.name,
                ),
            )
            clusterStateManager.save(clusterState)

            displayAccessInfo(activeResult.endpoint, activeResult.dashboardsEndpoint)
        } else {
            eventBus.emit(Event.OpenSearch.CreateStarted)
            eventBus.emit(Event.OpenSearch.CreateTimingNote)
            eventBus.emit(Event.OpenSearch.CreateStatusCheckHint)
        }
    }

    private fun generateDomainName(clusterName: String): String {
        // OpenSearch domain names must be 3-28 lowercase characters
        val baseName = clusterName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val suffix = "-os"
        val maxBaseLength = Constants.OpenSearch.DOMAIN_NAME_MAX_LENGTH - suffix.length
        val truncatedBase =
            if (baseName.length > maxBaseLength) {
                baseName.take(maxBaseLength)
            } else {
                baseName
            }
        return "$truncatedBase$suffix".trimEnd('-')
    }

    private fun displayAccessInfo(
        endpoint: String?,
        dashboardsEndpoint: String?,
    ) {
        eventBus.emit(Event.OpenSearch.DomainCreatedSuccessfully)
        if (endpoint != null) {
            eventBus.emit(Event.OpenSearch.AccessInfo(endpoint, dashboardsEndpoint))
        }
    }
}
