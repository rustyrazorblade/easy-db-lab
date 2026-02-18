package com.rustyrazorblade.easydblab.commands.opensearch

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.aws.DomainState
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainResult
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.opensearch.model.ResourceNotFoundException

/**
 * Test suite for OpenSearchStatus command.
 *
 * Tests domain status checking, --endpoint flag behavior,
 * state synchronization, and error handling.
 */
class OpenSearchStatusTest : BaseKoinTest() {
    private lateinit var mockOpenSearchService: OpenSearchService
    private lateinit var mockClusterStateManager: ClusterStateManager

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    private val testOpenSearchState =
        OpenSearchClusterState(
            domainName = "test-cluster-os",
            domainId = "123456789012/test-cluster-os",
            endpoint = "search-test.us-west-2.es.amazonaws.com",
            dashboardsEndpoint = "https://search-test.us-west-2.es.amazonaws.com/_dashboards",
            state = "ACTIVE", // Must match DomainState.ACTIVE.name exactly
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<OpenSearchService>().also { mockOpenSearchService = it } }
                single { mock<ClusterStateManager>().also { mockClusterStateManager = it } }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockOpenSearchService = getKoin().get()
        mockClusterStateManager = getKoin().get()
    }

    @Nested
    inner class NoOpenSearchDomain {
        @Test
        fun `execute should show message when no OpenSearch domain configured`() {
            val stateWithoutOpenSearch =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    hosts =
                        mutableMapOf(
                            ServerType.Control to listOf(testControlHost),
                        ),
                    openSearchDomain = null,
                )

            whenever(mockClusterStateManager.load()).thenReturn(stateWithoutOpenSearch)

            val command = OpenSearchStatus()
            command.execute()

            verify(mockOpenSearchService, never()).describeDomain(any())
        }

        @Test
        fun `execute with --endpoint should output nothing when no domain configured`() {
            val stateWithoutOpenSearch =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    hosts =
                        mutableMapOf(
                            ServerType.Control to listOf(testControlHost),
                        ),
                    openSearchDomain = null,
                )

            whenever(mockClusterStateManager.load()).thenReturn(stateWithoutOpenSearch)

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            verify(mockOpenSearchService, never()).describeDomain(any())
        }
    }

    @Nested
    inner class DomainStatusChecks {
        @Test
        fun `execute should check domain status and display details when ready`() {
            val domainResult =
                OpenSearchDomainResult(
                    domainName = "test-cluster-os",
                    domainId = "123456789012/test-cluster-os",
                    endpoint = "search-test.us-west-2.es.amazonaws.com",
                    dashboardsEndpoint = "https://search-test.us-west-2.es.amazonaws.com/_dashboards",
                    state = DomainState.ACTIVE,
                )

            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os")).thenReturn(domainResult)

            val command = OpenSearchStatus()
            command.execute()

            verify(mockOpenSearchService).describeDomain("test-cluster-os")
        }

        @Test
        fun `execute should show creating status when endpoint not available`() {
            val domainResult =
                OpenSearchDomainResult(
                    domainName = "test-cluster-os",
                    domainId = "123456789012/test-cluster-os",
                    endpoint = null,
                    dashboardsEndpoint = null,
                    state = DomainState.PROCESSING,
                )

            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os")).thenReturn(domainResult)

            val command = OpenSearchStatus()
            command.execute()

            verify(mockOpenSearchService).describeDomain("test-cluster-os")
        }

        @Test
        fun `execute should handle deleted domain state`() {
            val domainResult =
                OpenSearchDomainResult(
                    domainName = "test-cluster-os",
                    domainId = "123456789012/test-cluster-os",
                    endpoint = null,
                    dashboardsEndpoint = null,
                    state = DomainState.DELETED,
                )

            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os")).thenReturn(domainResult)

            val command = OpenSearchStatus()
            command.execute()

            verify(mockOpenSearchService).describeDomain("test-cluster-os")
        }
    }

    @Nested
    inner class EndpointOnlyFlag {
        @Test
        fun `execute with --endpoint should output only endpoint URL`() {
            val domainResult =
                OpenSearchDomainResult(
                    domainName = "test-cluster-os",
                    domainId = "123456789012/test-cluster-os",
                    endpoint = "search-test.us-west-2.es.amazonaws.com",
                    dashboardsEndpoint = "https://search-test.us-west-2.es.amazonaws.com/_dashboards",
                    state = DomainState.ACTIVE,
                )

            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os")).thenReturn(domainResult)

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            verify(mockOpenSearchService).describeDomain("test-cluster-os")
            // State should not be saved when using --endpoint flag (output only mode)
            verify(mockClusterStateManager, never()).save(any())
        }

        @Test
        fun `execute with --endpoint should output nothing when no endpoint available`() {
            val domainResult =
                OpenSearchDomainResult(
                    domainName = "test-cluster-os",
                    domainId = "123456789012/test-cluster-os",
                    endpoint = null,
                    dashboardsEndpoint = null,
                    state = DomainState.PROCESSING,
                )

            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os")).thenReturn(domainResult)

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            verify(mockOpenSearchService).describeDomain("test-cluster-os")
        }
    }

    @Nested
    inner class StateUpdates {
        @Test
        fun `execute should update local state when remote state differs`() {
            // Remote has updated endpoint that differs from local state
            val domainResult =
                OpenSearchDomainResult(
                    domainName = "test-cluster-os",
                    domainId = "123456789012/test-cluster-os",
                    endpoint = "new-endpoint.us-west-2.es.amazonaws.com",
                    dashboardsEndpoint = "https://new-endpoint.us-west-2.es.amazonaws.com/_dashboards",
                    state = DomainState.ACTIVE,
                )

            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os")).thenReturn(domainResult)

            val command = OpenSearchStatus()
            command.execute()

            // State should be saved because remote endpoint differs from local
            verify(mockClusterStateManager).save(any())
        }

        @Test
        fun `execute should not update state when remote matches local`() {
            // Remote matches local state exactly
            val domainResult =
                OpenSearchDomainResult(
                    domainName = "test-cluster-os",
                    domainId = "123456789012/test-cluster-os",
                    endpoint = "search-test.us-west-2.es.amazonaws.com",
                    dashboardsEndpoint = "https://search-test.us-west-2.es.amazonaws.com/_dashboards",
                    state = DomainState.ACTIVE,
                )

            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os")).thenReturn(domainResult)

            val command = OpenSearchStatus()
            command.execute()

            // State should NOT be saved because remote matches local
            verify(mockClusterStateManager, never()).save(any())
        }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `execute should clear state when domain not found`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os"))
                .thenThrow(
                    ResourceNotFoundException
                        .builder()
                        .message("Domain not found")
                        .build(),
                )

            val command = OpenSearchStatus()
            command.execute()

            // State should be cleared and saved
            verify(mockClusterStateManager).save(any())
            assertThat(stateWithOpenSearch.openSearchDomain).isNull()
        }

        @Test
        fun `execute should show cached state on transient errors`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os"))
                .thenThrow(RuntimeException("Network error"))

            val command = OpenSearchStatus()
            command.execute()

            // State should NOT be cleared for transient errors
            verify(mockClusterStateManager, never()).save(any())
            assertThat(stateWithOpenSearch.openSearchDomain).isNotNull
        }

        @Test
        fun `execute with --endpoint should not output on ResourceNotFoundException`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os"))
                .thenThrow(
                    ResourceNotFoundException
                        .builder()
                        .message("Domain not found")
                        .build(),
                )

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            // State should be cleared and saved even with --endpoint flag
            verify(mockClusterStateManager).save(any())
        }

        @Test
        fun `execute with --endpoint should not output on transient errors`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)
            whenever(mockOpenSearchService.describeDomain("test-cluster-os"))
                .thenThrow(RuntimeException("Network error"))

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            // State should NOT be modified for transient errors
            verify(mockClusterStateManager, never()).save(any())
        }
    }

    private fun createStateWithOpenSearch(): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            hosts =
                mutableMapOf(
                    ServerType.Control to listOf(testControlHost),
                ),
            openSearchDomain = testOpenSearchState,
        )
}
