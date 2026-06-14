package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.DefaultKitEndpointResolver
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.KitEndpointResolver
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

class KitRunnerCommandTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockGrafanaDashboardService: GrafanaDashboardService = mock()
    private val mockWorkloadStepExecutor: WorkloadStepExecutor = mock()
    private val mockMetricsRegistryService: MetricsRegistryService = mock()
    private val mockKitHookExecutor: KitHookExecutor = mock()

    private lateinit var workingDir: File

    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-ctrl",
        )

    private val dbHost =
        ClusterHost(
            publicIp = "3.4.5.6",
            privateIp = "10.0.2.1",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
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
                    ServerType.Cassandra to listOf(dbHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<GrafanaDashboardService> { mockGrafanaDashboardService }
                single<WorkloadStepExecutor> { mockWorkloadStepExecutor }
                single<MetricsRegistryService> { mockMetricsRegistryService }
                single<KitHookExecutor> { mockKitHookExecutor }
                single<KitEndpointResolver> { DefaultKitEndpointResolver() }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        whenever(mockGrafanaDashboardService.installDashboardFromFile(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockWorkloadStepExecutor.execute(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockMetricsRegistryService.register(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockMetricsRegistryService.deregister(any(), any())).thenReturn(Result.success(Unit))
        workingDir = get<Context>().workingDirectory
    }

    private fun writeScript(
        kitName: String,
        scriptName: String,
        content: String,
    ): File {
        val binDir = File(workingDir, "$kitName/bin").also { it.mkdirs() }
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
        kitName: String,
        phaseName: String,
    ) = KitRunnerCommand(kitName, File(workingDir, kitName), phaseName)

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

        verify(mockGrafanaDashboardService).installDashboardFromFile(any(), any(), any())
    }

    @Test
    fun `dashboards skipped when start fails`() {
        writeScript("mydb", "start", "exit 1")
        val dashboardsDir = File(workingDir, "mydb/dashboards").also { it.mkdirs() }
        File(dashboardsDir, "test.json").writeText("{\"title\":\"Test\"}")

        command("mydb", "start").call()

        verify(mockGrafanaDashboardService, never()).installDashboardFromFile(any(), any(), any())
    }

    @Test
    fun `dashboards skipped for non-start scripts`() {
        writeScript("mydb", "stop", "exit 0")
        val dashboardsDir = File(workingDir, "mydb/dashboards").also { it.mkdirs() }
        File(dashboardsDir, "test.json").writeText("{\"title\":\"Test\"}")

        command("mydb", "stop").call()

        verify(mockGrafanaDashboardService, never()).installDashboardFromFile(any(), any(), any())
    }

    @Test
    fun `dashboards skipped when dashboards directory does not exist`() {
        writeScript("mydb", "start", "exit 0")

        command("mydb", "start").call()

        verify(mockGrafanaDashboardService, never()).installDashboardFromFile(any(), any(), any())
    }

    @Test
    fun `backup name injected as BACKUP_NAME env var when --name provided`() {
        val outputFile = File(workingDir, "backup_name.txt")
        writeScript("mydb", "backup", "echo \"\$BACKUP_NAME\" > \"${outputFile.absolutePath}\"")
        val cmd = command("mydb", "backup")
        cmd.name = "my-backup-20240101"
        cmd.call()
        assertThat(outputFile.readText().trim()).isEqualTo("my-backup-20240101")
    }

    @Test
    fun `BACKUP_NAME defaults to timestamp when --name not provided`() {
        val outputFile = File(workingDir, "backup_name.txt")
        writeScript("mydb", "backup", "echo \"\$BACKUP_NAME\" > \"${outputFile.absolutePath}\"")
        command("mydb", "backup").call()
        assertThat(outputFile.readText().trim()).matches("backup-\\d{8}-\\d{6}")
    }

    private fun writeKitYaml(
        kitName: String,
        yaml: String,
    ) {
        val dir = File(workingDir, kitName).also { it.mkdirs() }
        File(dir, Constants.Kit.CONFIG_FILE).writeText(yaml)
    }

    @Test
    fun `typed phase succeeds with exit code zero`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )
        val exitCode = command("mydb", "start").call()
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `typed phase failure returns exit code one and rethrows`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )
        whenever(mockWorkloadStepExecutor.execute(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("step failed")))

        assertThatThrownBy { command("mydb", "start").call() }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("step failed")
    }

    @Test
    fun `typed start phase registers scrape metrics`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            metrics:
              - type: scrape
                port: 9100
                path: /metrics
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )
        command("mydb", "start").call()
        verify(mockMetricsRegistryService).register(any(), any(), any())
    }

    @Test
    fun `typed start phase skips metrics registration when no metrics config`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )
        command("mydb", "start").call()
        verify(mockMetricsRegistryService, never()).register(any(), any(), any())
    }

    @Test
    fun `typed stop phase deregisters metrics`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            stop:
              - type: shell
                script: echo bye
            """.trimIndent(),
        )
        command("mydb", "stop").call()
        verify(mockMetricsRegistryService).deregister(any(), any())
    }

    @Test
    fun `typed phase installs dashboards from config dashboards list`() {
        val kitDir = File(workingDir, "mydb").also { it.mkdirs() }
        File(kitDir, "overview.json").writeText("{}")
        writeKitYaml(
            "mydb",
            """
            name: mydb
            dashboards:
              - path: overview.json
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )
        command("mydb", "start").call()
        verify(mockGrafanaDashboardService).installDashboardFromFile(any(), any(), any())
    }

    @Test
    fun `typed backup phase passes BACKUP_NAME via --name option`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            backup:
              - type: shell
                script: echo backup
            """.trimIndent(),
        )
        val cmd = command("mydb", "backup")
        cmd.name = "snap-2024"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        verify(mockWorkloadStepExecutor).execute(any(), any(), any())
    }

    @Test
    fun `typed install phase routed correctly`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            install:
              - type: shell
                script: echo install
            """.trimIndent(),
        )
        val exitCode = command("mydb", "install").call()
        assertThat(exitCode).isEqualTo(0)
        verify(mockWorkloadStepExecutor).execute(any(), any(), any())
    }

    @Test
    fun `typed uninstall phase routed correctly`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            uninstall:
              - type: shell
                script: echo uninstall
            """.trimIndent(),
        )
        val exitCode = command("mydb", "uninstall").call()
        assertThat(exitCode).isEqualTo(0)
        verify(mockWorkloadStepExecutor).execute(any(), any(), any())
    }

    @Test
    fun `unknown phase with no matching script throws error`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            start:
              - type: shell
                script: echo start
            """.trimIndent(),
        )

        assertThatThrownBy { command("mydb", "frobnicate").call() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("frobnicate")
    }

    @Test
    fun `typed restore phase routed correctly`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            restore:
              - type: shell
                script: echo restore
            """.trimIndent(),
        )
        val cmd = command("mydb", "restore")
        cmd.name = "snap-2024"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        verify(mockWorkloadStepExecutor).execute(any(), any(), any())
    }

    @Test
    fun `script start phase calls addRunningWorkload on success`() {
        writeScript("mydb", "start", "exit 0")
        command("mydb", "start").call()
        verify(mockClusterStateManager).addRunningWorkload("mydb")
    }

    @Test
    fun `script stop phase calls removeRunningWorkload on success`() {
        writeScript("mydb", "stop", "exit 0")
        command("mydb", "stop").call()
        verify(mockClusterStateManager).removeRunningWorkload("mydb")
    }

    @Test
    fun `script start phase does not call addRunningWorkload on failure`() {
        writeScript("mydb", "start", "exit 1")
        command("mydb", "start").call()
        verify(mockClusterStateManager, never()).addRunningWorkload(any())
    }

    @Test
    fun `script stop phase deregisters metrics`() {
        writeScript("mydb", "stop", "exit 0")
        command("mydb", "stop").call()
        verify(mockMetricsRegistryService).deregister(any(), any())
    }

    @Test
    fun `script start phase registers scrape metrics when config has metrics`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            metrics:
              - type: scrape
                port: 9100
                path: /metrics
            """.trimIndent(),
        )
        writeScript("mydb", "start", "exit 0")
        command("mydb", "start").call()
        verify(mockMetricsRegistryService).register(any(), any(), any())
    }

    @Test
    fun `typed start phase calls addRunningWorkload on success`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )
        command("mydb", "start").call()
        verify(mockClusterStateManager).addRunningWorkload("mydb")
    }

    @Test
    fun `typed stop phase calls removeRunningWorkload on success`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            stop:
              - type: shell
                script: echo bye
            """.trimIndent(),
        )
        command("mydb", "stop").call()
        verify(mockClusterStateManager).removeRunningWorkload("mydb")
    }

    private fun writeResolvedArgs(
        kitName: String,
        vars: Map<String, String>,
    ) {
        val dir = File(workingDir, kitName).also { it.mkdirs() }
        File(dir, Constants.Kit.RESOLVED_ARGS_FILE).writeText(
            vars.entries.joinToString("\n") { (k, v) -> "$k=$v" } + "\n",
        )
    }

    @Test
    fun `kit with kit-ref arg and valid target injects TARGET_JDBC_URL into script env`() {
        // Write target kit.yaml with JDBC endpoint
        File(File(workingDir, "clickhouse").also { it.mkdirs() }, "kit.yaml").writeText(
            """
            name: clickhouse
            capabilities:
              - type: sql
                user: default
                driver-class: com.clickhouse.jdbc.ClickHouseDriver
            endpoints:
              - name: JDBC
                node-type: db
                port: 8123
                type: jdbc
                scheme: clickhouse
                path: /default
            """.trimIndent(),
        )
        // Write bench kit.yaml with kit-ref arg
        writeKitYaml(
            "sysbench-clickhouse",
            """
            name: sysbench
            args:
              - flag: --target
                variable: TARGET
                type: kit-ref
            """.trimIndent(),
        )
        writeResolvedArgs("sysbench-clickhouse", mapOf("TARGET" to "clickhouse"))

        val outputFile = File(workingDir, "target_jdbc_url.txt")
        writeScript(
            "sysbench-clickhouse",
            "start",
            """echo "${'$'}TARGET_JDBC_URL" > "${outputFile.absolutePath}"""",
        )

        command("sysbench-clickhouse", "start").call()

        assertThat(outputFile.readText().trim()).isEqualTo("jdbc:clickhouse://10.0.2.1:8123/default")
    }

    @Test
    fun `kit with kit-ref arg and missing target dir produces no TARGET vars and no exception`() {
        writeKitYaml(
            "sysbench-missing",
            """
            name: sysbench
            args:
              - flag: --target
                variable: TARGET
                type: kit-ref
            """.trimIndent(),
        )
        writeResolvedArgs("sysbench-missing", mapOf("TARGET" to "doesnotexist"))

        val outputFile = File(workingDir, "target_url.txt")
        writeScript(
            "sysbench-missing",
            "start",
            """echo "${'$'}TARGET_JDBC_URL" > "${outputFile.absolutePath}"""",
        )

        val exitCode = command("sysbench-missing", "start").call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(outputFile.readText().trim()).isEmpty()
    }

    // -------------------------------------------------------------------------
    // runtime arg env layering (kit-command-args)
    // -------------------------------------------------------------------------

    @Test
    fun `runtime arg value overrides install-time resolved arg for same variable`() {
        writeResolvedArgs("mydb", mapOf("THROUGHPUT" to "100"))
        val outputFile = File(workingDir, "throughput.txt")
        writeScript("mydb", "start", """echo "${'$'}THROUGHPUT" > "${outputFile.absolutePath}"""")

        val cmd = command("mydb", "start")
        cmd.runtimeArgValues["THROUGHPUT"] = "50000"
        cmd.call()

        assertThat(outputFile.readText().trim()).isEqualTo("50000")
    }

    @Test
    fun `cluster state var is not shadowed by runtime arg with same variable name`() {
        val outputFile = File(workingDir, "cluster_name.txt")
        writeScript("mydb", "start", """echo "${'$'}CLUSTER_NAME" > "${outputFile.absolutePath}"""")

        val cmd = command("mydb", "start")
        cmd.runtimeArgValues["CLUSTER_NAME"] = "injected-by-kit"
        cmd.call()

        assertThat(outputFile.readText().trim()).isEqualTo("test-cluster")
    }

    @Test
    fun `kit without kit-ref arg does not inject TARGET vars`() {
        val outputFile = File(workingDir, "target_url.txt")
        writeScript(
            "mydb",
            "start",
            """echo "${'$'}TARGET_JDBC_URL" > "${outputFile.absolutePath}"""",
        )

        command("mydb", "start").call()

        assertThat(outputFile.readText().trim()).isEmpty()
    }

    @Test
    fun `bare executable script without sh suffix is found and run`() {
        val binDir = File(workingDir, "mydb/bin").also { it.mkdirs() }
        val script = File(binDir, "run")
        val outputFile = File(workingDir, "bare-output.txt")
        script.writeText("#!/bin/sh\necho bare > \"${outputFile.absolutePath}\"\n")
        val perms =
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            )
        java.nio.file.Files
            .setPosixFilePermissions(script.toPath(), perms)

        val exitCode = command("mydb", "run").call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(outputFile.readText().trim()).isEqualTo("bare")
    }
}
