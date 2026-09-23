package com.rustyrazorblade.easydblab.commands.install

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.io.File
import java.nio.file.attribute.PosixFilePermission

/**
 * Script-driven kit phases (`bin/<phase>.sh`, or a bare executable): exit codes, the environment
 * the script sees, and what a successful start or stop does afterwards (dashboards, running
 * workloads, metrics).
 */
class KitRunnerCommandScriptTest : KitRunnerCommandTestBase() {
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
