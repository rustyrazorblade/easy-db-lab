package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClickHouseConfig
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ClickHouseInitTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = getKoin().get()
    }

    private fun createTestState(): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
        )

    @Test
    fun `init saves s3 cache size to cluster state`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.s3CacheSize = "50Gi"
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig).isNotNull
        assertThat(savedState.clickHouseConfig!!.s3CacheSize).isEqualTo("50Gi")
    }

    @Test
    fun `init uses default s3 cache size when not specified`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig).isNotNull
        assertThat(savedState.clickHouseConfig!!.s3CacheSize).isEqualTo(Constants.ClickHouse.DEFAULT_S3_CACHE_SIZE)
    }

    @Test
    fun `init overwrites existing clickhouse config`() {
        val state = createTestState()
        state.clickHouseConfig = ClickHouseConfig(s3CacheSize = "5Gi")
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.s3CacheSize = "100Gi"
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig!!.s3CacheSize).isEqualTo("100Gi")
    }
}
