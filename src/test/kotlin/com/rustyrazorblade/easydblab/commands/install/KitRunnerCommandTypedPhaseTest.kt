package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Typed kit phases declared in `kit.yaml`: routing each phase to the step executor, its exit
 * code and failure, and the post-phase actions (metrics, dashboards, running workloads).
 */
class KitRunnerCommandTypedPhaseTest : KitRunnerCommandTestBase() {
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
    fun `typed phase failure exits non-zero without rethrowing the step failure it already reported`() {
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

        // The step executor reported the failure as a typed event; rethrowing it would make the
        // command executor print it a second time as a raw exception class name.
        val exitCode = command("mydb", "start").call()

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        verify(mockClusterStateManager, never()).addRunningWorkload(any())
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

    /**
     * Uninstalling a kit that is still running must not leave its metrics ConfigMap (labelled
     * `easydblab.com/kit`, so the kit's own label-scoped deletes miss it), its OTel scrape job,
     * or its `runningKits` entry behind: uninstall releases it the way `stop` does.
     */
    @Test
    fun `uninstalling a running kit deregisters its metrics and forgets it is running`() {
        clusterState.runningKits = setOf("mydb")
        writeKitYaml(
            "mydb",
            """
            name: mydb
            uninstall:
              - type: shell
                script: echo uninstall
            """.trimIndent(),
        )

        assertThat(command("mydb", "uninstall").call()).isEqualTo(0)

        verify(mockMetricsRegistryService).deregister(any(), eq("mydb"))
        verify(mockClusterStateManager).removeRunningWorkload("mydb")
        verify(mockKitHookExecutor).firePostKitStop("mydb")
    }

    @Test
    fun `uninstalling a kit that is not running leaves the metrics and running kits alone`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            uninstall:
              - type: shell
                script: echo uninstall
            """.trimIndent(),
        )

        assertThat(command("mydb", "uninstall").call()).isEqualTo(0)

        verify(mockMetricsRegistryService, never()).deregister(any(), any())
        verify(mockClusterStateManager, never()).removeRunningWorkload(any())
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
}
