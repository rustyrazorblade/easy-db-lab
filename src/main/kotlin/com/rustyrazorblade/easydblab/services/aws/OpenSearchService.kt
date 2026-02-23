package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyAction
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyDocument
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyDocument.Companion.toJson
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyPrincipal
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyResource
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyStatement
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.opensearch.model.ClusterConfig
import software.amazon.awssdk.services.opensearch.model.CreateDomainRequest
import software.amazon.awssdk.services.opensearch.model.DeleteDomainRequest
import software.amazon.awssdk.services.opensearch.model.DescribeDomainRequest
import software.amazon.awssdk.services.opensearch.model.DomainEndpointOptions
import software.amazon.awssdk.services.opensearch.model.EBSOptions
import software.amazon.awssdk.services.opensearch.model.EncryptionAtRestOptions
import software.amazon.awssdk.services.opensearch.model.ListDomainNamesRequest
import software.amazon.awssdk.services.opensearch.model.NodeToNodeEncryptionOptions
import software.amazon.awssdk.services.opensearch.model.OpenSearchPartitionInstanceType
import software.amazon.awssdk.services.opensearch.model.Tag
import software.amazon.awssdk.services.opensearch.model.VPCOptions
import software.amazon.awssdk.services.opensearch.model.VolumeType

/**
 * State of an OpenSearch domain.
 */
enum class DomainState {
    ACTIVE,
    PROCESSING,
    DELETED,
    ;

    companion object {
        fun fromDomainStatus(
            processing: Boolean,
            deleted: Boolean,
        ): DomainState =
            when {
                deleted -> DELETED
                processing -> PROCESSING
                else -> ACTIVE
            }
    }
}

/**
 * Configuration for creating an OpenSearch domain.
 *
 * @property domainName Unique name for the OpenSearch domain (3-28 characters, lowercase, numbers, hyphens)
 * @property instanceType OpenSearch instance type (e.g., "t3.small.search", "r5.large.search")
 * @property instanceCount Number of data nodes in the cluster
 * @property ebsVolumeSize Size of EBS volume in GB for each data node
 * @property engineVersion OpenSearch engine version (e.g., "OpenSearch_2.11")
 * @property subnetId VPC subnet ID for the domain (required for VPC deployment)
 * @property securityGroupIds Security group IDs for network access control
 * @property accountId AWS account ID for constructing the domain ARN in access policy
 * @property region AWS region for constructing the domain ARN in access policy
 * @property tags Resource tags for the domain
 */
data class OpenSearchDomainConfig(
    val domainName: String,
    val instanceType: String = Constants.OpenSearch.DEFAULT_INSTANCE_TYPE,
    val instanceCount: Int = Constants.OpenSearch.DEFAULT_INSTANCE_COUNT,
    val ebsVolumeSize: Int = Constants.OpenSearch.DEFAULT_EBS_SIZE_GB,
    val engineVersion: String = "OpenSearch_${Constants.OpenSearch.DEFAULT_VERSION}",
    val subnetId: String,
    val securityGroupIds: List<String>,
    val accountId: String,
    val region: String,
    val tags: Map<String, String> = emptyMap(),
)

/**
 * Result of an OpenSearch domain operation.
 *
 * @property domainName The domain name
 * @property domainId AWS-assigned domain ID
 * @property endpoint The domain REST API endpoint (null while creating)
 * @property dashboardsEndpoint URL to OpenSearch Dashboards UI
 * @property state Current domain state
 */
data class OpenSearchDomainResult(
    val domainName: String,
    val domainId: String,
    val endpoint: String? = null,
    val dashboardsEndpoint: String? = null,
    val state: DomainState,
)

/**
 * Service for creating and managing AWS OpenSearch domains.
 *
 * This service handles the lifecycle of OpenSearch domains including creation,
 * status monitoring, and deletion. It follows the same patterns as EMRService
 * for consistency.
 *
 * OpenSearch domains typically take 10-30 minutes to create. The service provides
 * polling methods to wait for domain activation.
 */
class OpenSearchService(
    private val openSearchClient: OpenSearchClient,
    private val aws: AWS,
    private val eventBus: EventBus = EventBus(),
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Ensures the OpenSearch service-linked role exists.
     *
     * OpenSearch requires a service-linked role to access VPC resources.
     * Delegates to [AWS.ensureOpenSearchServiceLinkedRole].
     */
    fun ensureServiceLinkedRole() = aws.ensureOpenSearchServiceLinkedRole()

    /**
     * Retrieves the AWS account ID for the authenticated credentials.
     *
     * @return The AWS account ID
     */
    fun getAccountId(): String = aws.getAccountId()

    /**
     * Creates a new OpenSearch domain with the specified configuration.
     *
     * The domain will be created in VPC mode with encryption at rest and
     * node-to-node encryption enabled for security.
     *
     * @param config Domain configuration
     * @return Result containing domain ID and initial status
     */
    fun createDomain(config: OpenSearchDomainConfig): OpenSearchDomainResult {
        log.info { "Creating OpenSearch domain: ${config.domainName}" }
        eventBus.emit(Event.OpenSearch.Creating(config.domainName))

        val tags =
            config.tags.map { (key, value) ->
                Tag
                    .builder()
                    .key(key)
                    .value(value)
                    .build()
            }

        val clusterConfig =
            ClusterConfig
                .builder()
                .instanceType(OpenSearchPartitionInstanceType.fromValue(config.instanceType))
                .instanceCount(config.instanceCount)
                .dedicatedMasterEnabled(false)
                .zoneAwarenessEnabled(false)
                .build()

        val ebsOptions =
            EBSOptions
                .builder()
                .ebsEnabled(true)
                .volumeType(VolumeType.GP3)
                .volumeSize(config.ebsVolumeSize)
                .build()

        val vpcOptions =
            VPCOptions
                .builder()
                .subnetIds(config.subnetId)
                .securityGroupIds(config.securityGroupIds)
                .build()

        val encryptionAtRest =
            EncryptionAtRestOptions
                .builder()
                .enabled(true)
                .build()

        val nodeToNode =
            NodeToNodeEncryptionOptions
                .builder()
                .enabled(true)
                .build()

        val domainEndpointOptions =
            DomainEndpointOptions
                .builder()
                .enforceHTTPS(true)
                .build()

        // Generate access policy allowing all AWS principals (VPC security groups provide network-level access control)
        val accessPolicy = buildAccessPolicy(config.region, config.accountId, config.domainName)

        val request =
            CreateDomainRequest
                .builder()
                .domainName(config.domainName)
                .engineVersion(config.engineVersion)
                .clusterConfig(clusterConfig)
                .ebsOptions(ebsOptions)
                .vpcOptions(vpcOptions)
                .encryptionAtRestOptions(encryptionAtRest)
                .nodeToNodeEncryptionOptions(nodeToNode)
                .domainEndpointOptions(domainEndpointOptions)
                .accessPolicies(accessPolicy)
                .tagList(tags)
                .build()

        val response =
            RetryUtil.withAwsRetry("create-opensearch-domain") {
                openSearchClient.createDomain(request)
            }

        val domainStatus = response.domainStatus()

        log.info { "OpenSearch domain creation initiated: ${domainStatus.domainId()}" }
        eventBus.emit(Event.OpenSearch.Initiated(domainStatus.domainName()))

        return OpenSearchDomainResult(
            domainName = domainStatus.domainName(),
            domainId = domainStatus.domainId(),
            endpoint = domainStatus.endpoint(),
            dashboardsEndpoint = domainStatus.endpoint()?.let { getDashboardsEndpoint(it) },
            state = DomainState.fromDomainStatus(domainStatus.processing(), domainStatus.deleted()),
        )
    }

    /**
     * Gets the current status of an OpenSearch domain.
     *
     * @param domainName The domain name
     * @return Current domain status
     */
    fun describeDomain(domainName: String): OpenSearchDomainResult {
        val request =
            DescribeDomainRequest
                .builder()
                .domainName(domainName)
                .build()

        val response =
            RetryUtil.withAwsRetry("describe-opensearch-domain") {
                openSearchClient.describeDomain(request)
            }

        val domainStatus = response.domainStatus()

        // VPC domains use endpoints() map with "vpc" key, public domains use endpoint()
        val endpoint = domainStatus.endpoint() ?: domainStatus.endpoints()?.get("vpc")

        return OpenSearchDomainResult(
            domainName = domainStatus.domainName(),
            domainId = domainStatus.domainId(),
            endpoint = endpoint,
            dashboardsEndpoint = endpoint?.let { getDashboardsEndpoint(it) },
            state = DomainState.fromDomainStatus(domainStatus.processing(), domainStatus.deleted()),
        )
    }

    /**
     * Deletes an OpenSearch domain.
     *
     * @param domainName The domain name to delete
     */
    fun deleteDomain(domainName: String) {
        log.info { "Deleting OpenSearch domain: $domainName" }
        eventBus.emit(Event.OpenSearch.Deleting(domainName))

        val request =
            DeleteDomainRequest
                .builder()
                .domainName(domainName)
                .build()

        RetryUtil.withAwsRetry("delete-opensearch-domain") {
            openSearchClient.deleteDomain(request)
        }

        log.info { "OpenSearch domain deletion initiated: $domainName" }
    }

    /**
     * Checks if a domain is active (not processing).
     *
     * @param domainName The domain name
     * @return true if domain is active and ready for use
     */
    fun isDomainActive(domainName: String): Boolean {
        val result = describeDomain(domainName)
        return result.state == DomainState.ACTIVE
    }

    /**
     * Waits for an OpenSearch domain to become active.
     *
     * OpenSearch domains typically take 10-30 minutes to create. This method
     * polls the domain status until it becomes active or times out.
     *
     * @param domainName The domain name
     * @param timeoutMs Maximum time to wait in milliseconds (default: 45 minutes)
     * @param pollIntervalMs Interval between status checks (default: 30 seconds)
     * @return Domain result with endpoint information
     * @throws IllegalStateException if timeout is exceeded
     */
    fun waitForDomainActive(
        domainName: String,
        timeoutMs: Long = Constants.OpenSearch.MAX_POLL_TIMEOUT_MS,
        pollIntervalMs: Long = Constants.OpenSearch.POLL_INTERVAL_MS,
    ): OpenSearchDomainResult {
        log.info { "Waiting for OpenSearch domain $domainName to become active..." }
        eventBus.emit(Event.Message("Waiting for OpenSearch domain to become active (this may take 10-30 minutes)..."))

        val startTime = System.currentTimeMillis()
        var pollCount = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val result = describeDomain(domainName)
            pollCount++

            val readyResult = checkDomainActiveState(result, domainName, pollCount, startTime)
            if (readyResult != null) {
                return readyResult
            }

            Thread.sleep(pollIntervalMs)
        }

        error("Timeout waiting for OpenSearch domain $domainName to become active after ${timeoutMs}ms")
    }

    /**
     * Checks the domain state during active waiting and returns the result if ready.
     *
     * @return The domain result if ready, null if still waiting
     * @throws IllegalStateException if domain was deleted
     */
    private fun checkDomainActiveState(
        result: OpenSearchDomainResult,
        domainName: String,
        pollCount: Int,
        startTime: Long,
    ): OpenSearchDomainResult? =
        when (result.state) {
            DomainState.ACTIVE -> handleActiveState(result, domainName, pollCount, startTime)
            DomainState.DELETED -> error("OpenSearch domain $domainName was deleted")
            DomainState.PROCESSING -> {
                logPollProgress(pollCount, startTime, "Domain still processing")
                null
            }
        }

    /**
     * Handles the ACTIVE state, returning the result if endpoint is available.
     */
    private fun handleActiveState(
        result: OpenSearchDomainResult,
        domainName: String,
        pollCount: Int,
        startTime: Long,
    ): OpenSearchDomainResult? =
        if (result.endpoint != null) {
            log.info { "OpenSearch domain $domainName is active with endpoint: ${result.endpoint}" }
            eventBus.emit(Event.OpenSearch.Ready)
            result
        } else {
            logPollProgress(pollCount, startTime, "Domain active, waiting for endpoint")
            null
        }

    /**
     * Logs progress at regular intervals during polling.
     */
    private fun logPollProgress(
        pollCount: Int,
        startTime: Long,
        message: String,
    ) {
        if (pollCount % Constants.OpenSearch.LOG_INTERVAL_POLLS == 0) {
            val elapsedMinutes = (System.currentTimeMillis() - startTime) / Constants.Time.MILLIS_PER_MINUTE
            eventBus.emit(Event.OpenSearch.WaitProgress(message, elapsedMinutes))
        }
    }

    /**
     * Waits for an OpenSearch domain to be fully deleted.
     *
     * OpenSearch domains take 10-20+ minutes to fully delete. This method
     * polls the domain status until it no longer exists or times out.
     *
     * @param domainName The domain name
     * @param timeoutMs Maximum time to wait in milliseconds (default: 45 minutes)
     * @param pollIntervalMs Interval between status checks (default: 30 seconds)
     * @throws IllegalStateException if timeout is exceeded
     */
    fun waitForDomainDeleted(
        domainName: String,
        timeoutMs: Long = Constants.OpenSearch.MAX_POLL_TIMEOUT_MS,
        pollIntervalMs: Long = Constants.OpenSearch.POLL_INTERVAL_MS,
    ) {
        log.info { "Waiting for OpenSearch domain $domainName to be deleted..." }
        eventBus.emit(Event.Message("Waiting for OpenSearch domain $domainName to be deleted (this may take 10-20 minutes)..."))

        val startTime = System.currentTimeMillis()
        var pollCount = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            pollCount++

            if (checkDomainDeletedState(domainName, pollCount, startTime)) {
                return
            }

            Thread.sleep(pollIntervalMs)
        }

        error("Timeout waiting for OpenSearch domain $domainName to be deleted after ${timeoutMs}ms")
    }

    /**
     * Checks if domain is deleted during deletion polling.
     *
     * @return true if domain is deleted, false if still waiting
     */
    private fun checkDomainDeletedState(
        domainName: String,
        pollCount: Int,
        startTime: Long,
    ): Boolean =
        try {
            val result = describeDomain(domainName)
            handleDeletedPollResult(result, domainName, pollCount, startTime)
        } catch (e: software.amazon.awssdk.services.opensearch.model.ResourceNotFoundException) {
            log.info { "OpenSearch domain $domainName no longer exists (deleted)" }
            eventBus.emit(Event.OpenSearch.Deleted(domainName))
            true
        }

    /**
     * Handles poll result during deletion waiting.
     *
     * @return true if domain is deleted, false if still waiting
     */
    private fun handleDeletedPollResult(
        result: OpenSearchDomainResult,
        domainName: String,
        pollCount: Int,
        startTime: Long,
    ): Boolean =
        when (result.state) {
            DomainState.DELETED -> {
                log.info { "OpenSearch domain $domainName is deleted" }
                eventBus.emit(Event.OpenSearch.Deleted(domainName))
                true
            }
            DomainState.ACTIVE, DomainState.PROCESSING -> {
                logPollProgress(pollCount, startTime, "Domain still deleting")
                false
            }
        }

    /**
     * Constructs the OpenSearch Dashboards URL from the domain endpoint.
     *
     * @param endpoint The domain REST API endpoint
     * @return Full URL to OpenSearch Dashboards
     */
    fun getDashboardsEndpoint(endpoint: String): String = "https://$endpoint/_dashboards"

    /**
     * Builds the resource-based access policy for an OpenSearch domain.
     *
     * This policy allows all AWS principals to access the domain. Since the domain
     * is deployed in a VPC with security groups, network-level access control is
     * already enforced. This is the standard pattern for VPC-only OpenSearch domains.
     *
     * @param region AWS region for the domain ARN
     * @param accountId AWS account ID for the domain ARN
     * @param domainName Name of the OpenSearch domain
     * @return JSON string containing the access policy
     */
    fun buildAccessPolicy(
        region: String,
        accountId: String,
        domainName: String,
    ): String =
        IamPolicyDocument(
            statement =
                listOf(
                    IamPolicyStatement(
                        effect = "Allow",
                        principal = IamPolicyPrincipal.aws("*"),
                        action = IamPolicyAction.single("es:*"),
                        resource = IamPolicyResource.single("arn:aws:es:$region:$accountId:domain/$domainName/*"),
                    ),
                ),
        ).toJson()

    /**
     * Finds OpenSearch domains deployed in the specified VPC subnet.
     *
     * Iterates through all domains in the account and checks if they are
     * deployed in any of the given subnet IDs.
     *
     * @param subnetIds List of subnet IDs to search for domains in
     * @return List of domain names deployed in the specified subnets
     */
    fun findDomainsInVpc(subnetIds: List<String>): List<String> {
        if (subnetIds.isEmpty()) {
            return emptyList()
        }

        log.info { "Finding OpenSearch domains in subnets: $subnetIds" }

        // List all domain names in the account
        val listRequest = ListDomainNamesRequest.builder().build()
        val domainNames =
            RetryUtil.withAwsRetry("list-opensearch-domains") {
                openSearchClient.listDomainNames(listRequest).domainNames().map { it.domainName() }
            }

        if (domainNames.isEmpty()) {
            return emptyList()
        }

        // Check each domain to see if it's in one of our subnets
        val domainsInVpc = mutableListOf<String>()
        for (domainName in domainNames) {
            try {
                val describeRequest =
                    DescribeDomainRequest
                        .builder()
                        .domainName(domainName)
                        .build()

                val domain =
                    RetryUtil.withAwsRetry("describe-opensearch-domain") {
                        openSearchClient.describeDomain(describeRequest).domainStatus()
                    }

                // Check if the domain's VPC config includes our subnets
                val domainSubnets = domain.vpcOptions()?.subnetIds() ?: emptyList()
                if (domainSubnets.any { it in subnetIds }) {
                    log.info { "Found OpenSearch domain $domainName in VPC" }
                    domainsInVpc.add(domainName)
                }
            } catch (e: Exception) {
                log.warn(e) { "Failed to describe domain $domainName, skipping" }
            }
        }

        log.info { "Found ${domainsInVpc.size} OpenSearch domains in specified subnets" }
        return domainsInVpc
    }
}
