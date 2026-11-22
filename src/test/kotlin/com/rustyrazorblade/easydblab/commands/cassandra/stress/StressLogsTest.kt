package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.output.RecordingNodeOutput
import com.rustyrazorblade.easydblab.services.StressJobService
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
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

    // Note: Test for "job name not provided" is not needed because jobName is a
    // required parameter (lateinit) - picocli will handle the validation

    private fun pod(
        name: String,
        status: String,
    ) = KubernetesPod(
        namespace = TestScopes.NAMESPACE,
        name = name,
        status = status,
        ready = "1/1",
        restarts = 0,
        age = Duration.ofMinutes(5),
    )

    @Test
    fun `each pod's status and log lines are tagged with the pod, and an empty pod says so`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(name = "test-cluster", hosts = mutableMapOf(ServerType.Control to listOf(testControlHost))),
        )
        whenever(mockStressJobService.getPodsForJob(eq("stress-test")))
            .thenReturn(Result.success(listOf(pod("stress-test-abc12", "Running"), pod("stress-test-def34", "Pending"))))
        whenever(mockStressJobService.getPodLogs(eq("stress-test-abc12"), isNull()))
            .thenReturn(Result.success("Starting stress test...\nRunning workload...\n"))
        whenever(mockStressJobService.getPodLogs(eq("stress-test-def34"), isNull()))
            .thenReturn(Result.success(""))

        StressLogs().apply { jobName = "stress-test" }.execute()

        val nodeOutput = getKoin().get<RecordingNodeOutput>()
        assertThat(nodeOutput.stdout).containsExactly(
            "[stress-test-abc12] status Running",
            "[stress-test-abc12] Starting stress test...",
            "[stress-test-abc12] Running workload...",
            "[stress-test-def34] status Pending",
            "[stress-test-def34] (no logs available yet)",
        )
        // Pod lines are never kept: pod names are unique per job, so a kept buffer would never be freed.
        assertThat(nodeOutput.tail("stress-test-abc12")).isEmpty()
        val events = (getKoin().get<OutputHandler>() as BufferedOutputHandler).messages
        assertThat(events.joinToString("\n")).doesNotContain("Starting stress test")
    }

    @Test
    fun `execute should use tail lines when specified`() {
        // Given - cluster state with control node
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        val testPods =
            listOf(
                KubernetesPod(
                    namespace = TestScopes.NAMESPACE,
                    name = "stress-test-1234567890-abc12",
                    status = "Running",
                    ready = "1/1",
                    restarts = 0,
                    age = Duration.ofMinutes(5),
                ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockStressJobService.getPodsForJob(any()))
            .thenReturn(Result.success(testPods))
        whenever(mockStressJobService.getPodLogs(any(), eq(100)))
            .thenReturn(Result.success("Last 100 lines..."))

        val command = StressLogs()
        command.jobName = "stress-test-1234567890"
        command.tailLines = 100

        // When
        command.execute()

        // Then
        verify(mockStressJobService).getPodLogs(
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
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockStressJobService.getPodsForJob(any()))
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
        val denied = KubernetesClientException("Failed to get pods", 403, null)
        // Given - cluster state with control node
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockStressJobService.getPodsForJob(any()))
            .thenReturn(Result.failure(denied))

        val command = StressLogs()
        command.jobName = "stress-test-1234567890"

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to get pods")
            .hasRootCause(denied)
    }
}
