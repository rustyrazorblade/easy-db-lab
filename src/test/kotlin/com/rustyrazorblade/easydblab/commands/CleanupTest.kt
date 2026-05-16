package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.K8sService
import io.fabric8.kubernetes.api.model.batch.v1.Job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CleanupTest : BaseKoinTest() {
    private val mockK8sService: K8sService = mock()
    private val mockClusterStateManager: ClusterStateManager = mock()
    private lateinit var outputHandler: BufferedOutputHandler

    private val controlHost = ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-control")
    private val dbNode0 = ClusterHost("54.2.0.0", "10.0.2.0", "db0", "us-west-2a", "i-db0")
    private val dbNode1 = ClusterHost("54.2.0.1", "10.0.2.1", "db1", "us-west-2b", "i-db1")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<K8sService> { mockK8sService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "test-bucket",
                initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
                hosts =
                    mapOf(
                        ServerType.Control to listOf(controlHost),
                        ServerType.Cassandra to listOf(dbNode0, dbNode1),
                    ),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockK8sService.createJob(any(), any(), any<Job>())).thenReturn(Result.success("cleanup-workload-0"))
    }

    private fun runCleanup(workload: String = "myworkload") {
        val cmd = Cleanup()
        cmd.workload = workload
        cmd.execute()
    }

    @Test
    fun `one job is created per db node`() {
        runCleanup()

        verify(mockK8sService, times(2)).createJob(any(), any(), any<Job>())
    }

    @Test
    fun `jobs target db namespace`() {
        val captor = argumentCaptor<Job>()

        runCleanup()

        verify(mockK8sService, times(2)).createJob(any(), any(), captor.capture())
        captor.allValues.forEach { job ->
            assertThat(job.metadata.namespace).isEqualTo(Constants.K8s.NAMESPACE)
        }
    }

    @Test
    fun `jobs have distinct names based on ordinal`() {
        val captor = argumentCaptor<Job>()

        runCleanup(workload = "clickhouse")

        verify(mockK8sService, times(2)).createJob(any(), any(), captor.capture())
        val names = captor.allValues.map { it.metadata.name }
        assertThat(names).containsExactlyInAnyOrder("cleanup-clickhouse-0", "cleanup-clickhouse-1")
    }

    @Test
    fun `each job targets the correct node ordinal`() {
        val captor = argumentCaptor<Job>()

        runCleanup()

        verify(mockK8sService, times(2)).createJob(any(), any(), captor.capture())
        val selectors = captor.allValues.map { it.spec.template.spec.nodeSelector }
        assertThat(selectors).anyMatch { it[Constants.NODE_ORDINAL_LABEL] == "0" }
        assertThat(selectors).anyMatch { it[Constants.NODE_ORDINAL_LABEL] == "1" }
    }

    @Test
    fun `job command deletes the workload data path`() {
        val captor = argumentCaptor<Job>()

        runCleanup(workload = "clickhouse")

        verify(mockK8sService, times(2)).createJob(any(), any(), captor.capture())
        captor.allValues.forEach { job ->
            val container =
                job.spec.template.spec.containers
                    .first()
            val fullCommand = container.command.joinToString(" ")
            assertThat(fullCommand).contains("rm -rf /mnt/db1/clickhouse")
        }
    }

    @Test
    fun `complete event is emitted after all jobs are created`() {
        runCleanup(workload = "presto")

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Cleanup complete for 'presto'")
    }

    @Test
    fun `job created event is emitted per node`() {
        runCleanup()

        val jobCreatedMessages = outputHandler.messages.filter { it.contains("Cleanup job created") }
        assertThat(jobCreatedMessages).hasSize(2)
    }
}
