package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.attribute.PosixFilePermission

class WorkloadRunnerCommandTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockGrafanaDashboardService: GrafanaDashboardService = mock()
    private val mockWorkloadStepExecutor: WorkloadStepExecutor = mock()
    private val mockMetricsRegistryService: MetricsRegistryService = mock()

    private lateinit var workingDir: File

    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-ctrl",
        )

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(controlHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<GrafanaDashboardService> { mockGrafanaDashboardService }
                single<WorkloadStepExecutor> { mockWorkloadStepExecutor }
                single<MetricsRegistryService> { mockMetricsRegistryService }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        whenever(mockGrafanaDashboardService.installDashboardFromFile(any(), any())).thenReturn(Result.success(Unit))
        workingDir = get<Context>().workingDirectory
    }

    private fun writeScript(
        workloadName: String,
        scriptName: String,
        content: String,
    ): File {
        val binDir = File(workingDir, "$workloadName/bin").also { it.mkdirs() }
        val script = File(binDir, "$scriptName.sh")
        script.writeText("#!/bin/sh\n$content\n")
        val perms =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        java.nio.file.Files
            .setPosixFilePermissions(script.toPath(), perms)
        return script
    }

    private fun command(
        workloadName: String,
        phaseName: String,
    ) = WorkloadRunnerCommand(workloadName, File(workingDir, workloadName), phaseName)

    @Test
    fun `exit code zero propagated from successful script`() {
        writeScript("mydb", "start", "exit 0")
        val exitCode = command("mydb", "start").call()
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `non-zero exit code propagated from failing script`() {
        writeScript("mydb", "start", "exit 1")
        val exitCode = command("mydb", "start").call()
        assertThat(exitCode).isEqualTo(1)
    }

    @Test
    fun `cluster name env var injected into script process`() {
        val outputFile = File(workingDir, "cluster_name.txt")
        writeScript("mydb", "run", "echo \"\$CLUSTER_NAME\" > \"${outputFile.absolutePath}\"")
        command("mydb", "run").call()
        assertThat(outputFile.readText().trim()).isEqualTo("test-cluster")
    }

    @Test
    fun `dashboards installed after successful start`() {
        writeScript("mydb", "start", "exit 0")
        val dashboardsDir = File(workingDir, "mydb/dashboards").also { it.mkdirs() }
        File(dashboardsDir, "test.json").writeText("{\"title\":\"Test\"}")

        command("mydb", "start").call()

        verify(mockGrafanaDashboardService).installDashboardFromFile(any(), any())
    }

    @Test
    fun `dashboards skipped when start fails`() {
        writeScript("mydb", "start", "exit 1")
        val dashboardsDir = File(workingDir, "mydb/dashboards").also { it.mkdirs() }
        File(dashboardsDir, "test.json").writeText("{\"title\":\"Test\"}")

        command("mydb", "start").call()

        verify(mockGrafanaDashboardService, never()).installDashboardFromFile(any(), any())
    }

    @Test
    fun `dashboards skipped for non-start scripts`() {
        writeScript("mydb", "stop", "exit 0")
        val dashboardsDir = File(workingDir, "mydb/dashboards").also { it.mkdirs() }
        File(dashboardsDir, "test.json").writeText("{\"title\":\"Test\"}")

        command("mydb", "stop").call()

        verify(mockGrafanaDashboardService, never()).installDashboardFromFile(any(), any())
    }

    @Test
    fun `dashboards skipped when dashboards directory does not exist`() {
        writeScript("mydb", "start", "exit 0")

        command("mydb", "start").call()

        verify(mockGrafanaDashboardService, never()).installDashboardFromFile(any(), any())
    }
}
