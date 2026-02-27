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
import java.io.File

class SparkDownTest : BaseKoinTest() {
    private lateinit var mockEmrService: EMRService
    private lateinit var testClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> {
        val stateFile = File(tempDir, "state.json")
        testClusterStateManager = ClusterStateManager(stateFile)

        return listOf(
            module {
                single {
                    mock<EMRService>().also {
                        mockEmrService = it
                    }
                }
                single { testClusterStateManager }
            },
        )
    }

    private fun saveState(state: ClusterState) {
        testClusterStateManager.save(state)
    }

    @Test
    fun `should fail when no EMR cluster exists`() {
        saveState(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
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
                versions = mutableMapOf(),
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
