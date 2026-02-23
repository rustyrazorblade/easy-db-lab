package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.AWS
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.opensearch.model.ClusterConfig
import software.amazon.awssdk.services.opensearch.model.CreateDomainRequest
import software.amazon.awssdk.services.opensearch.model.CreateDomainResponse
import software.amazon.awssdk.services.opensearch.model.DeleteDomainRequest
import software.amazon.awssdk.services.opensearch.model.DeleteDomainResponse
import software.amazon.awssdk.services.opensearch.model.DescribeDomainRequest
import software.amazon.awssdk.services.opensearch.model.DescribeDomainResponse
import software.amazon.awssdk.services.opensearch.model.DomainInfo
import software.amazon.awssdk.services.opensearch.model.DomainStatus
import software.amazon.awssdk.services.opensearch.model.EBSOptions
import software.amazon.awssdk.services.opensearch.model.ListDomainNamesRequest
import software.amazon.awssdk.services.opensearch.model.ListDomainNamesResponse
import software.amazon.awssdk.services.opensearch.model.ResourceNotFoundException
import software.amazon.awssdk.services.opensearch.model.ServiceSoftwareOptions
import software.amazon.awssdk.services.opensearch.model.VPCDerivedInfo
import software.amazon.awssdk.services.opensearch.model.VolumeType

/**
 * Tests for OpenSearchService verifying domain lifecycle operations.
 */
internal class OpenSearchServiceTest {
    private lateinit var mockOpenSearchClient: OpenSearchClient
    private lateinit var mockAws: AWS
    private lateinit var eventBus: EventBus
    private val capturedEvents = mutableListOf<EventEnvelope>()
    private lateinit var openSearchService: OpenSearchService

    @BeforeEach
    fun setUp() {
        mockOpenSearchClient = mock()
        mockAws = mock()
        capturedEvents.clear()
        eventBus = EventBus()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope)
                }

                override fun close() {}
            },
        )
        openSearchService = OpenSearchService(mockOpenSearchClient, mockAws, eventBus)
    }

    @Test
    fun `createDomain should create domain with correct configuration`() {
        val config =
            OpenSearchDomainConfig(
                domainName = "test-domain",
                instanceType = "t3.small.search",
                instanceCount = 1,
                ebsVolumeSize = 100,
                engineVersion = "OpenSearch_2.11",
                subnetId = "subnet-123",
                securityGroupIds = listOf("sg-123"),
                accountId = "123456789012",
                region = "us-west-2",
                tags = mapOf("easy_cass_lab" to "1"),
            )

        val domainStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = null,
                processing = true,
            )

        val response =
            CreateDomainResponse
                .builder()
                .domainStatus(domainStatus)
                .build()

        whenever(mockOpenSearchClient.createDomain(any<CreateDomainRequest>())).thenReturn(response)

        val result = openSearchService.createDomain(config)

        assertThat(result.domainName).isEqualTo("test-domain")
        assertThat(result.domainId).isEqualTo("123456789012/test-domain")
        assertThat(result.state).isEqualTo(DomainState.PROCESSING)
        verify(mockOpenSearchClient).createDomain(any<CreateDomainRequest>())
        assertThat(capturedEvents).anyMatch { it.event is Event.OpenSearch.Creating }
    }

    @Test
    fun `describeDomain should return domain status`() {
        val domainStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = "search-test-domain.us-west-2.es.amazonaws.com",
                processing = false,
            )

        val response =
            DescribeDomainResponse
                .builder()
                .domainStatus(domainStatus)
                .build()

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>())).thenReturn(response)

        val result = openSearchService.describeDomain("test-domain")

        assertThat(result.domainName).isEqualTo("test-domain")
        assertThat(result.endpoint).isEqualTo("search-test-domain.us-west-2.es.amazonaws.com")
        assertThat(result.state).isEqualTo(DomainState.ACTIVE)
    }

    @Test
    fun `deleteDomain should delete the domain`() {
        val domainStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = null,
                processing = true,
                deleted = true,
            )

        val response =
            DeleteDomainResponse
                .builder()
                .domainStatus(domainStatus)
                .build()

        whenever(mockOpenSearchClient.deleteDomain(any<DeleteDomainRequest>())).thenReturn(response)

        openSearchService.deleteDomain("test-domain")

        verify(mockOpenSearchClient).deleteDomain(any<DeleteDomainRequest>())
        assertThat(capturedEvents).anyMatch { it.event is Event.OpenSearch.Deleting }
    }

    @Test
    fun `isDomainActive should return true when domain is not processing`() {
        val domainStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = "search-test-domain.us-west-2.es.amazonaws.com",
                processing = false,
            )

        val response =
            DescribeDomainResponse
                .builder()
                .domainStatus(domainStatus)
                .build()

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>())).thenReturn(response)

        val result = openSearchService.isDomainActive("test-domain")

        assertThat(result).isTrue()
    }

    @Test
    fun `isDomainActive should return false when domain is processing`() {
        val domainStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = null,
                processing = true,
            )

        val response =
            DescribeDomainResponse
                .builder()
                .domainStatus(domainStatus)
                .build()

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>())).thenReturn(response)

        val result = openSearchService.isDomainActive("test-domain")

        assertThat(result).isFalse()
    }

    @Test
    fun `waitForDomainActive should return when domain becomes active`() {
        // First call: processing
        val processingStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = null,
                processing = true,
            )

        // Second call: active
        val activeStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = "search-test-domain.us-west-2.es.amazonaws.com",
                processing = false,
            )

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>()))
            .thenReturn(DescribeDomainResponse.builder().domainStatus(processingStatus).build())
            .thenReturn(DescribeDomainResponse.builder().domainStatus(activeStatus).build())

        val result = openSearchService.waitForDomainActive("test-domain", pollIntervalMs = 10)

        assertThat(result.endpoint).isEqualTo("search-test-domain.us-west-2.es.amazonaws.com")
        assertThat(result.state).isEqualTo(DomainState.ACTIVE)
    }

    @Test
    fun `waitForDomainActive should throw on timeout`() {
        val processingStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = null,
                processing = true,
            )

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>()))
            .thenReturn(DescribeDomainResponse.builder().domainStatus(processingStatus).build())

        assertThatThrownBy {
            openSearchService.waitForDomainActive("test-domain", timeoutMs = 50, pollIntervalMs = 10)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Timeout waiting for OpenSearch domain")
    }

    @Test
    fun `getDashboardsEndpoint should return correct URL`() {
        val endpoint = "search-test-domain.us-west-2.es.amazonaws.com"
        val dashboardsEndpoint = openSearchService.getDashboardsEndpoint(endpoint)

        assertThat(dashboardsEndpoint).isEqualTo("https://search-test-domain.us-west-2.es.amazonaws.com/_dashboards")
    }

    @Test
    fun `buildAccessPolicy should generate valid compact policy with correct ARN`() {
        val policy = openSearchService.buildAccessPolicy("us-west-2", "123456789012", "test-domain")

        // Verify it's compact JSON (no newlines)
        assertThat(policy).doesNotContain("\n")
        // Verify key policy elements
        assertThat(policy).contains("\"Version\":\"2012-10-17\"")
        assertThat(policy).contains("\"Effect\":\"Allow\"")
        assertThat(policy).contains("\"Principal\":{\"AWS\":\"*\"}")
        assertThat(policy).contains("\"Action\":\"es:*\"")
        assertThat(policy).contains("arn:aws:es:us-west-2:123456789012:domain/test-domain/*")
    }

    @Test
    fun `buildAccessPolicy should handle different regions and accounts`() {
        val policy = openSearchService.buildAccessPolicy("eu-central-1", "987654321098", "my-domain")

        assertThat(policy).contains("arn:aws:es:eu-central-1:987654321098:domain/my-domain/*")
    }

    @Test
    fun `waitForDomainDeleted should return when domain no longer exists`() {
        // Domain is deleting, then throws ResourceNotFoundException
        val deletingStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = null,
                processing = true,
                deleted = true,
            )

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>()))
            .thenReturn(DescribeDomainResponse.builder().domainStatus(deletingStatus).build())
            .thenThrow(
                ResourceNotFoundException
                    .builder()
                    .message("Domain not found")
                    .build(),
            )

        // Should complete without throwing
        openSearchService.waitForDomainDeleted("test-domain", pollIntervalMs = 10)

        assertThat(capturedEvents.map { it.event.toDisplayString() })
            .anyMatch { it.contains("Waiting for OpenSearch domain test-domain to be deleted") }
        assertThat(capturedEvents).anyMatch { it.event is Event.OpenSearch.Deleted }
    }

    @Test
    fun `waitForDomainDeleted should return immediately when ResourceNotFoundException thrown`() {
        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>()))
            .thenThrow(
                ResourceNotFoundException
                    .builder()
                    .message("Domain not found")
                    .build(),
            )

        // Should complete without throwing
        openSearchService.waitForDomainDeleted("test-domain", pollIntervalMs = 10)

        assertThat(capturedEvents).anyMatch { it.event is Event.OpenSearch.Deleted }
    }

    @Test
    fun `waitForDomainDeleted should return when domain state is Deleted`() {
        val deletedStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = null,
                processing = false,
                deleted = true,
            )

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>()))
            .thenReturn(DescribeDomainResponse.builder().domainStatus(deletedStatus).build())

        openSearchService.waitForDomainDeleted("test-domain", pollIntervalMs = 10)

        assertThat(capturedEvents).anyMatch { it.event is Event.OpenSearch.Deleted }
    }

    @Test
    fun `waitForDomainDeleted should throw on timeout`() {
        val processingStatus =
            createMockDomainStatus(
                domainName = "test-domain",
                domainId = "123456789012/test-domain",
                endpoint = "search-test-domain.us-west-2.es.amazonaws.com",
                processing = false,
            )

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>()))
            .thenReturn(DescribeDomainResponse.builder().domainStatus(processingStatus).build())

        assertThatThrownBy {
            openSearchService.waitForDomainDeleted("test-domain", timeoutMs = 50, pollIntervalMs = 10)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Timeout waiting for OpenSearch domain test-domain to be deleted")
    }

    @Test
    fun `findDomainsInVpc should return domains in specified subnets`() {
        val domainNames =
            listOf(
                DomainInfo.builder().domainName("domain-in-vpc").build(),
                DomainInfo.builder().domainName("domain-outside-vpc").build(),
            )

        whenever(mockOpenSearchClient.listDomainNames(any<ListDomainNamesRequest>()))
            .thenReturn(ListDomainNamesResponse.builder().domainNames(domainNames).build())

        // Domain in VPC with matching subnet
        val domainInVpc =
            createMockDomainStatusWithVpc(
                domainName = "domain-in-vpc",
                domainId = "123456789012/domain-in-vpc",
                subnetIds = listOf("subnet-123", "subnet-456"),
            )

        // Domain outside VPC with different subnets
        val domainOutsideVpc =
            createMockDomainStatusWithVpc(
                domainName = "domain-outside-vpc",
                domainId = "123456789012/domain-outside-vpc",
                subnetIds = listOf("subnet-789"),
            )

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<DescribeDomainRequest>(0)
                val status =
                    if (request.domainName() == "domain-in-vpc") domainInVpc else domainOutsideVpc
                DescribeDomainResponse.builder().domainStatus(status).build()
            }

        val result = openSearchService.findDomainsInVpc(listOf("subnet-123"))

        assertThat(result).containsExactly("domain-in-vpc")
    }

    @Test
    fun `findDomainsInVpc should return empty list when no subnets provided`() {
        val result = openSearchService.findDomainsInVpc(emptyList())

        assertThat(result).isEmpty()
    }

    @Test
    fun `findDomainsInVpc should return empty list when no domains exist`() {
        whenever(mockOpenSearchClient.listDomainNames(any<ListDomainNamesRequest>()))
            .thenReturn(ListDomainNamesResponse.builder().domainNames(emptyList()).build())

        val result = openSearchService.findDomainsInVpc(listOf("subnet-123"))

        assertThat(result).isEmpty()
    }

    @Test
    fun `findDomainsInVpc should handle describe errors gracefully`() {
        val domainNames =
            listOf(
                DomainInfo.builder().domainName("working-domain").build(),
                DomainInfo.builder().domainName("broken-domain").build(),
            )

        whenever(mockOpenSearchClient.listDomainNames(any<ListDomainNamesRequest>()))
            .thenReturn(ListDomainNamesResponse.builder().domainNames(domainNames).build())

        val workingDomain =
            createMockDomainStatusWithVpc(
                domainName = "working-domain",
                domainId = "123456789012/working-domain",
                subnetIds = listOf("subnet-123"),
            )

        whenever(mockOpenSearchClient.describeDomain(any<DescribeDomainRequest>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<DescribeDomainRequest>(0)
                if (request.domainName() == "broken-domain") {
                    throw RuntimeException("API error")
                }
                DescribeDomainResponse.builder().domainStatus(workingDomain).build()
            }

        val result = openSearchService.findDomainsInVpc(listOf("subnet-123"))

        // Should still return the working domain despite error on broken-domain
        assertThat(result).containsExactly("working-domain")
    }

    private fun createMockDomainStatus(
        domainName: String,
        domainId: String,
        endpoint: String?,
        processing: Boolean,
        deleted: Boolean = false,
    ): DomainStatus {
        val builder =
            DomainStatus
                .builder()
                .domainName(domainName)
                .domainId(domainId)
                .arn("arn:aws:es:us-west-2:123456789012:domain/$domainName")
                .created(true)
                .deleted(deleted)
                .processing(processing)
                .upgradeProcessing(false)
                .engineVersion("OpenSearch_2.11")
                .clusterConfig(
                    ClusterConfig
                        .builder()
                        .instanceType("t3.small.search")
                        .instanceCount(1)
                        .build(),
                ).ebsOptions(
                    EBSOptions
                        .builder()
                        .ebsEnabled(true)
                        .volumeType(VolumeType.GP3)
                        .volumeSize(100)
                        .build(),
                ).serviceSoftwareOptions(
                    ServiceSoftwareOptions
                        .builder()
                        .currentVersion("R20231023-P1")
                        .build(),
                )

        if (endpoint != null) {
            builder.endpoint(endpoint)
        }

        return builder.build()
    }

    private fun createMockDomainStatusWithVpc(
        domainName: String,
        domainId: String,
        subnetIds: List<String>,
    ): DomainStatus =
        DomainStatus
            .builder()
            .domainName(domainName)
            .domainId(domainId)
            .arn("arn:aws:es:us-west-2:123456789012:domain/$domainName")
            .created(true)
            .deleted(false)
            .processing(false)
            .upgradeProcessing(false)
            .engineVersion("OpenSearch_2.11")
            .endpoint("search-$domainName.us-west-2.es.amazonaws.com")
            .clusterConfig(
                ClusterConfig
                    .builder()
                    .instanceType("t3.small.search")
                    .instanceCount(1)
                    .build(),
            ).ebsOptions(
                EBSOptions
                    .builder()
                    .ebsEnabled(true)
                    .volumeType(VolumeType.GP3)
                    .volumeSize(100)
                    .build(),
            ).vpcOptions(
                VPCDerivedInfo
                    .builder()
                    .subnetIds(subnetIds)
                    .build(),
            ).serviceSoftwareOptions(
                ServiceSoftwareOptions
                    .builder()
                    .currentVersion("R20231023-P1")
                    .build(),
            ).build()
}
