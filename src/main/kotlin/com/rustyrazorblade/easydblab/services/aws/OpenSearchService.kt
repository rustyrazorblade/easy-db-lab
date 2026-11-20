package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyAction
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyDocument
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyDocument.Companion.toJson
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyPrincipal
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyResource
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyStatement
import com.rustyrazorblade.easydblab.providers.aws.IamRoles
import com.rustyrazorblade.easydblab.providers.aws.pollUntilOrTimeout
import com.rustyrazorblade.easydblab.providers.aws.withAwsRetry
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.exception.SdkException
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
import software.amazon.awssdk.services.opensearch.model.ResourceNotFoundException
import software.amazon.awssdk.services.opensearch.model.Tag
import software.amazon.awssdk.services.opensearch.model.VPCOptions
import software.amazon.awssdk.services.opensearch.model.VolumeType
import java.time.Duration

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
 * @property instanceType OpenSearch instance type (e.g., "r8g.xlarge.search", "m8g.large.search")
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
 * What a domain lookup found, as [OpenSearchService.lookupDomain] reports it. A value rather than an
 * SDK exception, so the command layer can tell a missing domain from AWS not answering.
 */
sealed interface DomainLookup {
    /** The domain exists; [result] is its current status. */
    data class Found(
        val result: OpenSearchDomainResult,
    ) : DomainLookup

    /** AWS reports no domain by that name. */
    data object NotFound : DomainLookup

    /** AWS could not answer; [reason] is the SDK's message. */
    data class Unavailable(
        val reason: String,
    ) : DomainLookup
}

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
    private val iamRoles: IamRoles,
    private val accountIdentity: AccountIdentity,
    private val eventBus: EventBus = EventBus(),
    private val errorHandler: ProviderErrorHandler,
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** Failures [lookupDomain] leaves to the command failure reporter instead of reporting as unavailable. */
        private val PROPAGATED_KINDS = setOf(ProviderErrorKind.CREDENTIALS, ProviderErrorKind.PERMISSION_DENIED)
    }

    /**
     * Ensures the OpenSearch service-linked role exists.
     *
     * OpenSearch requires a service-linked role to access VPC resources.
     * Delegates to [AWS.ensureOpenSearchServiceLinkedRole].
     */
    fun ensureServiceLinkedRole() = iamRoles.ensureOpenSearchServiceLinkedRole()

    /**
     * Retrieves the AWS account ID for the authenticated credentials.
     *
     * @return The AWS account ID
     */
    fun getAccountId(): String = accountIdentity.getAccountId()

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

        val request = buildCreateDomainRequest(config)

        val response =
            withAwsRetry("create-opensearch-domain") {
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

    private fun buildCreateDomainRequest(config: OpenSearchDomainConfig): CreateDomainRequest {
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

        val accessPolicy = buildAccessPolicy(config.region, config.accountId, config.domainName)

        return CreateDomainRequest
            .builder()
            .domainName(config.domainName)
            .engineVersion(config.engineVersion)
            .clusterConfig(clusterConfig)
            .ebsOptions(ebsOptions)
            .vpcOptions(vpcOptions)
            .encryptionAtRestOptions(
                EncryptionAtRestOptions.builder().enabled(true).build(),
            ).nodeToNodeEncryptionOptions(
                NodeToNodeEncryptionOptions.builder().enabled(true).build(),
            ).domainEndpointOptions(
                DomainEndpointOptions.builder().enforceHTTPS(true).build(),
            ).accessPolicies(accessPolicy)
            .tagList(tags)
            .build()
    }

    /**
     * Looks a domain up and reports the outcome as a value: found, not found, or unavailable when
     * AWS could not answer (a network failure, a throttle that outlasted the retry).
     *
     * The outcome is a value, so callers outside the AWS adapter need no SDK exception type. The
     * catch sits outside the retry on purpose: translating an error inside a retried call would hide
     * it from the retry's predicate. A credentials or permission failure propagates, so the command
     * failure reporter shows it with its remedy (the EasyDBLabOpenSearch policy for a denial); so
     * does any failure that is not an AWS SDK failure.
     *
     * @param domainName The domain name
     * @return What AWS reported for [domainName]
     */
    fun lookupDomain(domainName: String): DomainLookup =
        try {
            DomainLookup.Found(describeDomain(domainName))
        } catch (_: ResourceNotFoundException) {
            DomainLookup.NotFound
        } catch (e: SdkException) {
            if (errorHandler.describe(e)?.kind in PROPAGATED_KINDS) throw e
            log.warn(e) { "Failed to describe OpenSearch domain $domainName" }
            DomainLookup.Unavailable(e.message ?: e::class.simpleName.orEmpty())
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
            withAwsRetry("describe-opensearch-domain") {
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

        withAwsRetry("delete-opensearch-domain") {
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
     * polls the domain status until it becomes active or times out. A describe call that
     * fails (for example, a throttle) is retried within the timeout.
     *
     * @param domainName The domain name
     * @param timeoutMs Maximum time to wait in milliseconds (default: 45 minutes)
     * @param pollIntervalMs Interval between status checks (default: 30 seconds)
     * @return Domain result with endpoint information
     * @throws AwsTimeoutException if the domain is not active within [timeoutMs]
     * @throws IllegalStateException if the domain was deleted
     */
    fun waitForDomainActive(
        domainName: String,
        timeoutMs: Long = Constants.OpenSearch.MAX_POLL_TIMEOUT_MS,
        pollIntervalMs: Long = Constants.OpenSearch.POLL_INTERVAL_MS,
    ): OpenSearchDomainResult {
        log.info { "Waiting for OpenSearch domain $domainName to become active..." }
        eventBus.emit(Event.OpenSearch.WaitingForActive)

        val startTime = System.currentTimeMillis()
        var pollCount = 0

        val result =
            pollUntilOrTimeout(
                what = "OpenSearch domain $domainName to become active",
                timeout = Duration.ofMillis(timeoutMs),
                interval = Duration.ofMillis(pollIntervalMs),
                done = { it.state == DomainState.DELETED || it.isReady() },
            ) {
                describeDomain(domainName).also { logActiveProgress(it, ++pollCount, startTime) }
            }

        check(result.state != DomainState.DELETED) { "OpenSearch domain $domainName was deleted" }
        log.info { "OpenSearch domain $domainName is active with endpoint: ${result.endpoint}" }
        eventBus.emit(Event.OpenSearch.Ready)
        return result
    }

    private fun OpenSearchDomainResult.isReady(): Boolean = state == DomainState.ACTIVE && endpoint != null

    /**
     * Logs progress at regular intervals while the domain is not yet ready.
     */
    private fun logActiveProgress(
        result: OpenSearchDomainResult,
        pollCount: Int,
        startTime: Long,
    ) {
        when {
            result.state == DomainState.PROCESSING -> logPollProgress(pollCount, startTime, "Domain still processing")
            result.state == DomainState.ACTIVE && !result.isReady() ->
                logPollProgress(pollCount, startTime, "Domain active, waiting for endpoint")
        }
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
     * polls the domain status until it no longer exists or times out. A describe call that
     * fails for any reason other than the domain not existing is retried within the timeout.
     *
     * @param domainName The domain name
     * @param timeoutMs Maximum time to wait in milliseconds (default: 45 minutes)
     * @param pollIntervalMs Interval between status checks (default: 30 seconds)
     * @throws AwsTimeoutException if the domain still exists after [timeoutMs]
     */
    fun waitForDomainDeleted(
        domainName: String,
        timeoutMs: Long = Constants.OpenSearch.MAX_POLL_TIMEOUT_MS,
        pollIntervalMs: Long = Constants.OpenSearch.POLL_INTERVAL_MS,
    ) {
        log.info { "Waiting for OpenSearch domain $domainName to be deleted..." }
        eventBus.emit(Event.OpenSearch.WaitingForDeleted(domainName))

        val startTime = System.currentTimeMillis()
        var pollCount = 0

        pollUntilOrTimeout(
            what = "OpenSearch domain $domainName to be deleted",
            timeout = Duration.ofMillis(timeoutMs),
            interval = Duration.ofMillis(pollIntervalMs),
            done = { it == DomainState.DELETED },
        ) {
            describeDomainStateOrDeleted(domainName).also { state ->
                pollCount++
                if (state != DomainState.DELETED) logPollProgress(pollCount, startTime, "Domain still deleting")
            }
        }

        log.info { "OpenSearch domain $domainName is deleted" }
        eventBus.emit(Event.OpenSearch.Deleted(domainName))
    }

    /**
     * Describes the domain, reading "no such domain" as [DomainState.DELETED].
     */
    private fun describeDomainStateOrDeleted(domainName: String): DomainState =
        try {
            describeDomain(domainName).state
        } catch (e: ResourceNotFoundException) {
            log.debug(e) { "OpenSearch domain $domainName no longer exists" }
            DomainState.DELETED
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
            withAwsRetry("list-opensearch-domains") {
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
                    withAwsRetry("describe-opensearch-domain") {
                        openSearchClient.describeDomain(describeRequest).domainStatus()
                    }

                // Check if the domain's VPC config includes our subnets
                val domainSubnets = domain.vpcOptions()?.subnetIds() ?: emptyList()
                if (domainSubnets.any { it in subnetIds }) {
                    log.info { "Found OpenSearch domain $domainName in VPC" }
                    domainsInVpc.add(domainName)
                }
            } catch (e: SdkException) {
                log.warn(e) { "Failed to describe domain $domainName, skipping" }
            }
        }

        log.info { "Found ${domainsInVpc.size} OpenSearch domains in specified subnets" }
        return domainsInVpc
    }
}
