package com.rustyrazorblade.easydblab.commands.opensearch

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for OpenSearchStop command following TDD principles.
 *
 * These tests verify OpenSearch domain deletion including
 * confirmation safety checks and cluster state management.
 */
class OpenSearchStopTest : BaseKoinTest() {
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
            state = "Active",
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

    @Test
    fun `command has correct default options`() {
        val command = OpenSearchStop()

        assertThat(command.force).isFalse()
    }

    @Test
    fun `execute should do nothing when no OpenSearch domain exists`() {
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

        val command = OpenSearchStop()
        command.force = true
        command.execute()

        verify(mockOpenSearchService, never()).deleteDomain(any())
    }

    @Test
    fun `execute without force flag should not delete domain`() {
        val stateWithOpenSearch =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
                openSearchDomain = testOpenSearchState,
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)

        val command = OpenSearchStop()
        command.force = false
        command.execute()

        verify(mockOpenSearchService, never()).deleteDomain(any())
    }

    @Test
    fun `execute with force flag should delete domain`() {
        val stateWithOpenSearch =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
                openSearchDomain = testOpenSearchState,
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)

        val command = OpenSearchStop()
        command.force = true
        command.execute()

        verify(mockOpenSearchService).deleteDomain("test-cluster-os")
        verify(mockClusterStateManager).save(any())
    }

    @Test
    fun `execute should clear OpenSearch state after deletion`() {
        val stateWithOpenSearch =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
                openSearchDomain = testOpenSearchState,
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithOpenSearch)

        val command = OpenSearchStop()
        command.force = true
        command.execute()

        // Verify the state was updated to clear OpenSearch domain
        assertThat(stateWithOpenSearch.openSearchDomain).isNull()
    }
}
