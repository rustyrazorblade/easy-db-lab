package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.services.StressJobService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Test suite for StressLogs command.
 *
 * These tests verify stress job log retrieval via StressJobService.
 */
class StressLogsTest : BaseKoinTest() {
    private lateinit var mockStressJobService: StressJobService
    private lateinit var mockClusterStateManager: ClusterStateManager

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<StressJobService>().also {
                        mockStressJobService = it
                    }
                }

                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockStressJobService = getKoin().get()
        mockClusterStateManager = getKoin().get()
    }

    @Test
    fun `execute should fail when no control nodes exist`() {
        // Given - cluster state with no control nodes
        val emptyState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            )

        whenever(mockClusterStateManager.load()).thenReturn(emptyState)

        val command = StressLogs()
        command.jobName = "stress-test-1234567890"

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    // Note: Test for "job name not provided" is not needed because jobName is a
    // required parameter (lateinit) - picocli will handle the validation

    @Test
    fun `execute should get logs for job pod`() {
        // Given - cluster state with control node
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        val testPods =
            listOf(
                KubernetesPod(
                    namespace = Constants.Stress.NAMESPACE,
                    name = "stress-test-1234567890-abc12",
                    status = "Running",
                    ready = "1/1",
                    restarts = 0,
                    age = Duration.ofMinutes(5),
                ),
            )

        val logOutput =
            """
            Starting stress test...
            Connecting to Cassandra...
            Running workload...
            """.trimIndent()

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockStressJobService.getPodsForJob(any(), any()))
            .thenReturn(Result.success(testPods))
        whenever(mockStressJobService.getPodLogs(any(), any(), isNull()))
            .thenReturn(Result.success(logOutput))

        val command = StressLogs()
        command.jobName = "stress-test-1234567890"

        // When
        command.execute()

        // Then - verify getPodsForJob and getPodLogs were called
        verify(mockStressJobService).getPodsForJob(
            eq(testControlHost),
            eq("stress-test-1234567890"),
        )
        verify(mockStressJobService).getPodLogs(
            eq(testControlHost),
            eq("stress-test-1234567890-abc12"),
            isNull(),
        )
    }

    @Test
    fun `execute should use tail lines when specified`() {
        // Given - cluster state with control node
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        val testPods =
            listOf(
                KubernetesPod(
                    namespace = Constants.Stress.NAMESPACE,
                    name = "stress-test-1234567890-abc12",
                    status = "Running",
                    ready = "1/1",
                    restarts = 0,
                    age = Duration.ofMinutes(5),
                ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockStressJobService.getPodsForJob(any(), any()))
            .thenReturn(Result.success(testPods))
        whenever(mockStressJobService.getPodLogs(any(), any(), eq(100)))
            .thenReturn(Result.success("Last 100 lines..."))

        val command = StressLogs()
        command.jobName = "stress-test-1234567890"
        command.tailLines = 100

        // When
        command.execute()

        // Then
        verify(mockStressJobService).getPodLogs(
            eq(testControlHost),
            eq("stress-test-1234567890-abc12"),
            eq(100),
        )
    }

    @Test
    fun `execute should fail when no pods found for job`() {
        // Given - cluster state with control node
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockStressJobService.getPodsForJob(any(), any()))
            .thenReturn(Result.success(emptyList()))

        val command = StressLogs()
        command.jobName = "stress-test-1234567890"

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No pods found")
    }

    @Test
    fun `execute should fail when getPodsForJob fails`() {
        // Given - cluster state with control node
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockStressJobService.getPodsForJob(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Failed to get pods")))

        val command = StressLogs()
        command.jobName = "stress-test-1234567890"

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to get pods")
    }
}
