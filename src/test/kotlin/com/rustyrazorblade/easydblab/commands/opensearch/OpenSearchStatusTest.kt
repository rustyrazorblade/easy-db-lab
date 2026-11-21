package com.rustyrazorblade.easydblab.commands.opensearch

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.aws.DomainLookup
import com.rustyrazorblade.easydblab.services.aws.DomainState
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainResult
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.io.File

/**
 * Test suite for OpenSearchStatus command.
 *
 * Tests domain status checking, --endpoint flag behavior,
 * state synchronization, and error handling.
 */
class OpenSearchStatusTest : BaseKoinTest() {
    private lateinit var mockOpenSearchService: OpenSearchService
    private lateinit var stateManager: ClusterStateManager
    private val stateFile by lazy { File(tempDir, "state.json") }

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
                single { ClusterStateManager(File(tempDir, "state.json")) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockOpenSearchService = getKoin().get()
        stateManager = getKoin().get()
    }

    @Nested
    inner class NoOpenSearchDomain {
        @Test
        fun `execute should show message when no OpenSearch domain configured`() {
            val stateWithoutOpenSearch =
                ClusterState(
                    name = "test-cluster",
                    hosts =
                        mutableMapOf(
                            ServerType.Control to listOf(testControlHost),
                        ),
                    openSearchDomain = null,
                )

            stateManager.replace(stateWithoutOpenSearch)

            val command = OpenSearchStatus()
            command.execute()

            verify(mockOpenSearchService, never()).lookupDomain(any())
        }

        @Test
        fun `execute with --endpoint should output nothing when no domain configured`() {
            val stateWithoutOpenSearch =
                ClusterState(
                    name = "test-cluster",
                    hosts =
                        mutableMapOf(
                            ServerType.Control to listOf(testControlHost),
                        ),
                    openSearchDomain = null,
                )

            stateManager.replace(stateWithoutOpenSearch)

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            verify(mockOpenSearchService, never()).lookupDomain(any())
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

            stateManager.replace(stateWithOpenSearch)
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.Found(domainResult))

            val command = OpenSearchStatus()
            command.execute()

            verify(mockOpenSearchService).lookupDomain("test-cluster-os")
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

            stateManager.replace(stateWithOpenSearch)
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.Found(domainResult))

            val command = OpenSearchStatus()
            command.execute()

            verify(mockOpenSearchService).lookupDomain("test-cluster-os")
        }

        @Test
        fun `a domain another command cleared during the lookup stays cleared`() {
            val domainResult =
                OpenSearchDomainResult(
                    domainName = "test-cluster-os",
                    domainId = "123456789012/test-cluster-os",
                    endpoint = "search-test.us-west-2.es.amazonaws.com",
                    dashboardsEndpoint = null,
                    state = DomainState.ACTIVE,
                )
            stateManager.replace(createStateWithOpenSearch())
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenAnswer {
                // `opensearch stop` runs while this lookup waits on the provider.
                stateManager.update { it.openSearchDomain = null }
                DomainLookup.Found(domainResult)
            }

            OpenSearchStatus().execute()

            assertThat(stateManager.load().openSearchDomain).isNull()
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

            stateManager.replace(stateWithOpenSearch)
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.Found(domainResult))

            val command = OpenSearchStatus()
            command.execute()

            verify(mockOpenSearchService).lookupDomain("test-cluster-os")
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

            stateManager.replace(stateWithOpenSearch)
            val before = stateFile.readText()
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.Found(domainResult))

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            verify(mockOpenSearchService).lookupDomain("test-cluster-os")
            // State should not be saved when using --endpoint flag (output only mode)
            assertThat(stateFile.readText()).isEqualTo(before)
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

            stateManager.replace(stateWithOpenSearch)
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.Found(domainResult))

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            verify(mockOpenSearchService).lookupDomain("test-cluster-os")
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

            stateManager.replace(stateWithOpenSearch)
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.Found(domainResult))

            val command = OpenSearchStatus()
            command.execute()

            assertThat(stateManager.load().openSearchDomain?.endpoint)
                .isEqualTo("new-endpoint.us-west-2.es.amazonaws.com")
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

            stateManager.replace(stateWithOpenSearch)
            val before = stateFile.readText()
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.Found(domainResult))

            val command = OpenSearchStatus()
            command.execute()

            // State should NOT be saved because remote matches local
            assertThat(stateFile.readText()).isEqualTo(before)
        }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `execute should clear state when domain not found`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            stateManager.replace(stateWithOpenSearch)
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.NotFound)

            val command = OpenSearchStatus()
            command.execute()

            assertThat(stateManager.load().openSearchDomain).isNull()
        }

        @Test
        fun `execute should show cached state on transient errors`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            stateManager.replace(stateWithOpenSearch)
            val before = stateFile.readText()
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os"))
                .thenReturn(DomainLookup.Unavailable("Network error"))

            val command = OpenSearchStatus()
            command.execute()

            assertThat(stateFile.readText()).describedAs("a transient error clears nothing").isEqualTo(before)
        }

        @Test
        fun `execute with --endpoint should not output when the domain is not found`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            stateManager.replace(stateWithOpenSearch)
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os")).thenReturn(DomainLookup.NotFound)

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            assertThat(stateManager.load().openSearchDomain).isNull()
        }

        @Test
        fun `a failure that is not an AWS failure propagates and leaves the state alone`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            stateManager.replace(stateWithOpenSearch)
            val before = stateFile.readText()
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os"))
                .thenThrow(IllegalStateException("bug"))

            assertThatThrownBy { OpenSearchStatus().execute() }.isInstanceOf(IllegalStateException::class.java)
            assertThat(stateFile.readText()).isEqualTo(before)
        }

        @Test
        fun `execute with --endpoint should not output on transient errors`() {
            val stateWithOpenSearch = createStateWithOpenSearch()

            stateManager.replace(stateWithOpenSearch)
            val before = stateFile.readText()
            whenever(mockOpenSearchService.lookupDomain("test-cluster-os"))
                .thenReturn(DomainLookup.Unavailable("Network error"))

            val command = OpenSearchStatus()
            command.endpointOnly = true
            command.execute()

            // State should NOT be modified for transient errors
            assertThat(stateFile.readText()).isEqualTo(before)
        }
    }

    private fun createStateWithOpenSearch(): ClusterState =
        ClusterState(
            name = "test-cluster",
            hosts =
                mutableMapOf(
                    ServerType.Control to listOf(testControlHost),
                ),
            openSearchDomain = testOpenSearchState,
        )
}
