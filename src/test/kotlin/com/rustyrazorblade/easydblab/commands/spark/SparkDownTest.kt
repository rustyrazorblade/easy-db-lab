package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.services.aws.EMRService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class SparkDownTest : BaseKoinTest() {
    private lateinit var mockEmrService: EMRService
    private val testClusterStateManager: ClusterStateManager get() = get()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<EMRService>().also {
                        mockEmrService = it
                    }
                }
            },
        )

    private fun saveState(state: ClusterState) {
        testClusterStateManager.replace(state)
    }

    @Test
    fun `should fail when no EMR cluster exists`() {
        saveState(
            ClusterState(
                name = "test",
            ),
        )

        get<EMRService>()

        val cmd = SparkDown()

        assertThatThrownBy { cmd.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No EMR cluster found")
    }

    @Test
    fun `should terminate cluster and clear state`() {
        saveState(
            ClusterState(
                name = "test",
                emrCluster =
                    EMRClusterState(
                        clusterId = "j-ABC123",
                        clusterName = "test-spark",
                        state = "WAITING",
                    ),
            ),
        )

        get<EMRService>()

        val cmd = SparkDown()
        cmd.execute()

        verify(mockEmrService).terminateCluster("j-ABC123")
        verify(mockEmrService).waitForClusterTerminated("j-ABC123")

        val savedState = testClusterStateManager.load()
        assertThat(savedState.emrCluster).isNull()
    }
}
