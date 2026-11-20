package com.rustyrazorblade.easydblab.commands.opensearch

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.aws.DomainState
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainConfig
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainResult
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Test suite for OpenSearchStart command following TDD principles.
 *
 * These tests verify OpenSearch domain creation including
 * AWS SDK integration and cluster state management.
 */
class OpenSearchStartTest : BaseKoinTest() {
    private lateinit var mockOpenSearchService: OpenSearchService
    private lateinit var stateManager: ClusterStateManager

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    private val testDbHost =
        ClusterHost(
            publicIp = "54.123.45.68",
            privateIp = "10.0.1.6",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test124",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<OpenSearchService>().also { mockOpenSearchService = it } }
                single { ClusterStateManager(File(tempDir, "state.json")) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockOpenSearchService = getKoin().get()
        stateManager = getKoin().get()
        whenever(mockOpenSearchService.getAccountId()).thenReturn("123456789012")
    }

    @Test
    fun `execute should fail when infrastructure is not ready`() {
        val stateWithoutInfrastructure =
            ClusterState(
                name = "test-cluster",
                hosts = mutableMapOf(),
                infrastructure = null,
            )

        stateManager.replace(stateWithoutInfrastructure)

        val command = OpenSearchStart()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Infrastructure not ready")
    }

    @Test
    fun `execute should fail when no subnet available`() {
        val stateWithEmptySubnets =
            ClusterState(
                name = "test-cluster",
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
                infrastructure =
                    InfrastructureState(
                        vpcId = "vpc-123",
                        region = "us-west-2",
                        subnetIds = emptyList(),
                        securityGroupId = "sg-123",
                    ),
            )

        stateManager.replace(stateWithEmptySubnets)

        val command = OpenSearchStart()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No subnet found")
    }

    @Test
    fun `execute should wait when wait flag is set`() {
        val domainResult =
            OpenSearchDomainResult(
                domainName = "test-cluster-opensearch",
                domainId = "123456789012/test-cluster-opensearch",
                endpoint = "search-test.us-west-2.es.amazonaws.com",
                dashboardsEndpoint = "https://search-test.us-west-2.es.amazonaws.com/_dashboards",
                state = DomainState.ACTIVE,
            )

        val stateWithInfrastructure = createValidClusterState()

        stateManager.replace(stateWithInfrastructure)
        whenever(mockOpenSearchService.createDomain(any())).thenReturn(domainResult)
        whenever(mockOpenSearchService.waitForDomainActive(any(), any(), any())).thenReturn(domainResult)

        val command = OpenSearchStart()
        command.wait = true
        command.execute()

        verify(mockOpenSearchService).createDomain(any())
        verify(mockOpenSearchService).waitForDomainActive(any(), any(), any())
        assertThat(stateManager.load().openSearchDomain?.endpoint).isEqualTo("search-test.us-west-2.es.amazonaws.com")
    }

    @Test
    fun `execute should not wait by default`() {
        val domainResult =
            OpenSearchDomainResult(
                domainName = "test-cluster-opensearch",
                domainId = "123456789012/test-cluster-opensearch",
                endpoint = null,
                dashboardsEndpoint = null,
                state = DomainState.PROCESSING,
            )

        val stateWithInfrastructure = createValidClusterState()

        stateManager.replace(stateWithInfrastructure)
        whenever(mockOpenSearchService.createDomain(any())).thenReturn(domainResult)

        val command = OpenSearchStart()
        // Default behavior: wait = false
        command.execute()

        verify(mockOpenSearchService).createDomain(any())
        verify(mockOpenSearchService, never()).waitForDomainActive(any(), any(), any())
    }

    @Test
    fun `execute should use custom instance type`() {
        val domainResult =
            OpenSearchDomainResult(
                domainName = "test-cluster-opensearch",
                domainId = "123456789012/test-cluster-opensearch",
                endpoint = "search-test.us-west-2.es.amazonaws.com",
                dashboardsEndpoint = "https://search-test.us-west-2.es.amazonaws.com/_dashboards",
                state = DomainState.ACTIVE,
            )

        val stateWithInfrastructure = createValidClusterState()

        stateManager.replace(stateWithInfrastructure)
        whenever(mockOpenSearchService.createDomain(any())).thenReturn(domainResult)
        whenever(mockOpenSearchService.waitForDomainActive(any(), any(), any())).thenReturn(domainResult)

        val command = OpenSearchStart()
        command.instanceType = "r5.large.search"
        command.execute()

        verify(mockOpenSearchService).createDomain(any())
    }

    @Test
    fun `the domain carries both marker tags and the cluster id`() {
        val domainResult =
            OpenSearchDomainResult(
                domainName = "test-cluster-opensearch",
                domainId = "123456789012/test-cluster-opensearch",
                endpoint = "search-test.us-west-2.es.amazonaws.com",
                dashboardsEndpoint = "https://search-test.us-west-2.es.amazonaws.com/_dashboards",
                state = DomainState.ACTIVE,
            )
        val state = createValidClusterState()
        stateManager.replace(state)
        whenever(mockOpenSearchService.createDomain(any())).thenReturn(domainResult)

        OpenSearchStart().execute()

        val config = argumentCaptor<OpenSearchDomainConfig>()
        verify(mockOpenSearchService).createDomain(config.capture())
        assertThat(config.firstValue.tags)
            .containsEntry(Constants.Vpc.LEGACY_TAG_KEY, Constants.Vpc.TAG_VALUE)
            .containsEntry(Constants.Vpc.TAG_KEY, Constants.Vpc.TAG_VALUE)
            .containsEntry(Constants.Vpc.CLUSTER_ID_TAG_KEY, state.clusterId)
    }

    private fun createValidClusterState(): ClusterState =
        ClusterState(
            name = "test-cluster",
            hosts =
                mutableMapOf(
                    ServerType.Control to listOf(testControlHost),
                    ServerType.Cassandra to listOf(testDbHost),
                ),
            infrastructure =
                InfrastructureState(
                    vpcId = "vpc-123",
                    region = "us-west-2",
                    subnetIds = listOf("subnet-123"),
                    securityGroupId = "sg-123",
                ),
            initConfig =
                InitConfig(
                    name = "test-cluster",
                    region = "us-west-2",
                ),
        )
}
