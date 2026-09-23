package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.KitMetrics
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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

    /**
     * A postgres extension instance overrides METRICS_PORT with its own metrics NodePort. That
     * port only means anything to a static `localhost:<port>` job; a pod-discovered target is
     * scraped on the pod IP at its container port, which the override must not replace.
     */
    @Test
    fun `a METRICS_PORT override replaces a static target's port but not a pod-discovered target's container port`() {
        writeKitYaml(
            "postgres-duckdb",
            """
            name: postgres
            metrics:
              - type: scrape
                port: 30987
                job: static
              - type: scrape
                port: 9187
                job: pods
                pod-selector: "cnpg.io/cluster=${'$'}{KIT_NAME}"
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )
        writeResolvedArgs("postgres-duckdb", mapOf("METRICS_PORT" to "30988"))

        command("postgres-duckdb", "start").call()

        val targets = argumentCaptor<List<KitMetrics.Scrape>>()
        verify(mockMetricsRegistryService).register(any(), eq("postgres-duckdb"), targets.capture())
        assertThat(targets.firstValue.associate { it.job to it.port }).isEqualTo(mapOf("static" to 30988, "pods" to 9187))
    }

    /**
     * Several instances of one kit (postgres and postgres-duckdb) run side by side, so a
     * pod-selector names its own instance with `${KIT_NAME}`, the same way the runtime
     * selector does. Unexpanded, it would match no pod label and scrape nothing.
     */
    @Test
    fun `a scrape pod-selector has KIT_NAME filled in with the kit instance name`() {
        writeKitYaml(
            "postgres-duckdb",
            """
            name: postgres
            metrics:
              - type: scrape
                port: 9187
                pod-selector: "cnpg.io/cluster=${'$'}{KIT_NAME},cnpg.io/instanceRole=primary"
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )

        command("postgres-duckdb", "start").call()

        val targets = argumentCaptor<List<KitMetrics.Scrape>>()
        verify(mockMetricsRegistryService).register(any(), any(), targets.capture())
        assertThat(targets.firstValue.single().podSelector)
            .isEqualTo("cnpg.io/cluster=postgres-duckdb,cnpg.io/instanceRole=primary")
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
