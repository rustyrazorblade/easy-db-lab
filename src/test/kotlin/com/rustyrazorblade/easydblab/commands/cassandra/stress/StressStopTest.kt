package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.services.StressJobService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Test suite for StressStop command.
 *
 * These tests verify stress job deletion via StressJobService.
 */
class StressStopTest : BaseKoinTest() {
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

        val command = StressStop()
        command.deleteAll = true

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute should fail when neither job name nor deleteAll is provided`() {
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

        val command = StressStop()
        // Neither jobName nor deleteAll is set

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("specify a job name")
    }

    @Test
    fun `execute should delete specific job when job name is provided`() {
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
        whenever(mockStressJobService.stopJob(any(), any())).thenReturn(Result.success(Unit))

        val command = StressStop()
        command.jobName = "stress-test-1234567890"

        // When
        command.execute()

        // Then - verify stopJob was called with the job name
        verify(mockStressJobService).stopJob(
            eq(testControlHost),
            eq("stress-test-1234567890"),
        )
    }

    @Test
    fun `execute should delete all jobs when deleteAll is true`() {
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

        val testJobs =
            listOf(
                KubernetesJob(
                    namespace = Constants.Stress.NAMESPACE,
                    name = "stress-test-1234567890",
                    status = "Complete",
                    completions = "1/1",
                    age = Duration.ofMinutes(5),
                ),
                KubernetesJob(
                    namespace = Constants.Stress.NAMESPACE,
                    name = "stress-another-1234567891",
                    status = "Running",
                    completions = "0/1",
                    age = Duration.ofMinutes(2),
                ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockStressJobService.listJobs(any())).thenReturn(Result.success(testJobs))
        whenever(mockStressJobService.stopJob(any(), any())).thenReturn(Result.success(Unit))

        val command = StressStop()
        command.deleteAll = true
        command.force = true // Need force=true to actually delete

        // When
        command.execute()

        // Then - both jobs should be deleted via stopJob
        verify(mockStressJobService, times(2)).stopJob(eq(testControlHost), any())
    }

    @Test
    fun `execute should fail when stopJob fails`() {
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
        whenever(mockStressJobService.stopJob(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Job deletion failed")))

        val command = StressStop()
        command.jobName = "stress-test-1234567890"

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("deletion failed")
    }

    @Test
    fun `execute should handle empty job list when deleteAll is true`() {
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
        whenever(mockStressJobService.listJobs(any())).thenReturn(Result.success(emptyList()))

        val command = StressStop()
        command.deleteAll = true

        // When - should not throw
        command.execute()

        // Then - no jobs to delete
        verify(mockStressJobService, never()).stopJob(any(), any())
    }
}
