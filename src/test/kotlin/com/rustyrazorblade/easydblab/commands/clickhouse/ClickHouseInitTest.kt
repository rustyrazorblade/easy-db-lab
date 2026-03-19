package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClickHouseConfig
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        state.clickHouseConfig = ClickHouseConfig(s3CacheSize = "5Gi", s3CacheOnWrite = "true")
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.s3CacheSize = "100Gi"
        command.s3CacheOnWrite = "false"
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig!!.s3CacheSize).isEqualTo("100Gi")
        assertThat(savedState.clickHouseConfig!!.s3CacheOnWrite).isEqualTo("false")
    }

    @Test
    fun `init uses default s3 cache on write when not specified`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig).isNotNull
        assertThat(savedState.clickHouseConfig!!.s3CacheOnWrite).isEqualTo(Constants.ClickHouse.DEFAULT_S3_CACHE_ON_WRITE)
    }

    @Test
    fun `init uses default replicas per shard when not specified`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig).isNotNull
        assertThat(savedState.clickHouseConfig!!.replicasPerShard)
            .isEqualTo(Constants.ClickHouse.DEFAULT_REPLICAS_PER_SHARD)
    }

    @Test
    fun `init rejects s3TierMoveFactor below 0`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.s3TierMoveFactor = -0.1

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("s3TierMoveFactor must be in range [0.0, 1.0]")
    }

    @Test
    fun `init rejects s3TierMoveFactor above 1`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.s3TierMoveFactor = 1.1

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("s3TierMoveFactor must be in range [0.0, 1.0]")
    }

    @Test
    fun `init accepts s3TierMoveFactor at lower boundary`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.s3TierMoveFactor = 0.0
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig!!.s3TierMoveFactor).isEqualTo(0.0)
    }

    @Test
    fun `init accepts s3TierMoveFactor at upper boundary`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.s3TierMoveFactor = 1.0
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig!!.s3TierMoveFactor).isEqualTo(1.0)
    }

    @Test
    fun `init uses default s3TierMoveFactor when not specified`() {
        val state = createTestState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = ClickHouseInit()
        command.execute()

        val captor = argumentCaptor<ClusterState>()
        verify(mockClusterStateManager).save(captor.capture())

        val savedState = captor.firstValue
        assertThat(savedState.clickHouseConfig).isNotNull
        assertThat(savedState.clickHouseConfig!!.s3TierMoveFactor)
            .isEqualTo(Constants.ClickHouse.DEFAULT_S3_TIER_MOVE_FACTOR)
    }
}
