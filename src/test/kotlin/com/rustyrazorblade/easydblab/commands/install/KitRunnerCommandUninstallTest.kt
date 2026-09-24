package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Duration

/**
 * Uninstalling a kit that is still running. Its uninstall steps remove only what `install`
 * created (ClickHouse's deletes Keeper and the operator), so without the kit's own `stop` first
 * the workload's objects — a ClickHouseInstallation whose finalizer the removed operator can no
 * longer process, its pods, Services and PVs — are orphaned.
 */
class KitRunnerCommandUninstallTest : KitRunnerCommandTestBase() {
    private val typedKit =
        """
        name: mydb
        runtime:
          type: pods
          selector: "easydblab/kit=mydb"
          namespace: db
        stop:
          - type: shell
            script: echo stop
        uninstall:
          - type: shell
            script: echo uninstall
        """.trimIndent()

    private val runningPod =
        KubernetesPod(namespace = "db", name = "mydb-0", status = "Running", ready = "1/1", restarts = 0, age = Duration.ofMinutes(5))

    @Test
    fun `uninstalling a running kit runs its stop steps, then its uninstall steps, then forgets it is running`() {
        clusterState.runningKits = setOf("mydb")
        writeKitYaml("mydb", typedKit)
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db")).thenReturn(Result.success(emptyList()))

        assertThat(command("mydb", "uninstall").call()).isEqualTo(0)

        inOrder(mockWorkloadStepExecutor, mockClusterStateManager) {
            verify(mockWorkloadStepExecutor).execute(any(), eq(Constants.Kit.PHASE_STOP), any())
            verify(mockWorkloadStepExecutor).execute(any(), eq(Constants.Kit.PHASE_UNINSTALL), any())
            verify(mockClusterStateManager).removeRunningWorkload("mydb")
        }
        assertThat(File(workingDir, "mydb")).doesNotExist()
    }

    @Test
    fun `a failed stop fails the uninstall without running the uninstall steps`() {
        clusterState.runningKits = setOf("mydb")
        writeKitYaml("mydb", typedKit)
        whenever(mockWorkloadStepExecutor.execute(any(), eq(Constants.Kit.PHASE_STOP), any()))
            .thenReturn(Result.failure(IllegalStateException("stop failed")))

        assertThat(command("mydb", "uninstall").call()).isEqualTo(Constants.ExitCodes.ERROR)

        verify(mockWorkloadStepExecutor, never()).execute(any(), eq(Constants.Kit.PHASE_UNINSTALL), any())
        verify(mockClusterStateManager, never()).removeRunningWorkload(any())
        assertThat(File(workingDir, "mydb")).isDirectory()
    }

    @Test
    fun `a stop whose workload outlives the wait fails the uninstall without running the uninstall steps`() {
        clusterState.runningKits = setOf("mydb")
        writeKitYaml("mydb", typedKit)
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db")).thenReturn(Result.success(listOf(runningPod)))

        assertThat(command("mydb", "uninstall").call()).isNotEqualTo(0)

        verify(mockWorkloadStepExecutor, never()).execute(any(), eq(Constants.Kit.PHASE_UNINSTALL), any())
        assertThat(File(workingDir, "mydb")).isDirectory()
    }

    @Test
    fun `uninstalling a running kit with a stop phase but no uninstall phase stops it and releases it`() {
        clusterState.runningKits = setOf("mydb")
        writeKitYaml(
            "mydb",
            """
            name: mydb
            stop:
              - type: shell
                script: echo stop
            """.trimIndent(),
        )

        assertThat(command("mydb", "uninstall").call()).isEqualTo(0)

        verify(mockWorkloadStepExecutor).execute(any(), eq(Constants.Kit.PHASE_STOP), any())
        verify(mockClusterStateManager).removeRunningWorkload("mydb")
        verify(mockMetricsRegistryService).deregister(any(), eq("mydb"))
        verify(mockKitHookExecutor).firePostKitStop("mydb")
        assertThat(File(workingDir, "mydb")).doesNotExist()
    }

    @Test
    fun `uninstalling a running kit with neither a stop nor an uninstall phase still releases it`() {
        clusterState.runningKits = setOf("mydb")
        writeKitYaml("mydb", "name: mydb")

        assertThat(command("mydb", "uninstall").call()).isEqualTo(0)

        verify(mockClusterStateManager).removeRunningWorkload("mydb")
        verify(mockMetricsRegistryService).deregister(any(), eq("mydb"))
        assertThat(File(workingDir, "mydb")).doesNotExist()
    }

    @Test
    fun `uninstalling a kit that is not running does not run its stop steps`() {
        writeKitYaml("mydb", typedKit)
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db")).thenReturn(Result.success(emptyList()))

        assertThat(command("mydb", "uninstall").call()).isEqualTo(0)

        verify(mockWorkloadStepExecutor, never()).execute(any(), eq(Constants.Kit.PHASE_STOP), any())
        verify(mockWorkloadStepExecutor).execute(any(), eq(Constants.Kit.PHASE_UNINSTALL), any())
    }

    /**
     * Presto's and Trino's uninstall is a `helm uninstall`, which returns while the release's
     * pods are still terminating. Uninstall waits for the kit's pods the way `stop` does.
     */
    @Test
    fun `uninstall waits until the kit's pods are gone`() {
        writeKitYaml("mydb", typedKit)
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db"))
            .thenReturn(Result.success(listOf(runningPod.copy(terminating = true))), Result.success(emptyList()))

        assertThat(command("mydb", "uninstall").call()).isEqualTo(0)

        verify(mockKubeService, times(2)).listPodsByLabel("easydblab/kit=mydb", "db")
        assertThat(File(workingDir, "mydb")).doesNotExist()
    }

    @Test
    fun `uninstall fails naming what is left, and keeps the kit directory, when the pods outlive the wait`() {
        writeKitYaml("mydb", typedKit)
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db")).thenReturn(Result.success(listOf(runningPod)))

        var exitCode = 0
        val events = captureEvents { exitCode = command("mydb", "uninstall").call() }

        assertThat(exitCode).isNotEqualTo(0)
        val incomplete = events.filterIsInstance<Event.Kit.StopIncomplete>().single()
        assertThat(incomplete.phase).isEqualTo(Constants.Kit.PHASE_UNINSTALL)
        assertThat(incomplete.toDisplayString()).contains("'mydb' uninstall ran", "pod/mydb-0", "easy-db-lab mydb uninstall")
        assertThat(File(workingDir, "mydb")).isDirectory()
    }

    @Test
    fun `uninstalling a running script-driven kit runs its stop script before its uninstall script`() {
        clusterState.runningKits = setOf("mydb")
        val log = File(workingDir, "phases.log")
        writeScript("mydb", "stop", "echo stop >> \"${log.absolutePath}\"")
        writeScript("mydb", "uninstall", "echo uninstall >> \"${log.absolutePath}\"")

        assertThat(command("mydb", "uninstall").call()).isEqualTo(0)

        assertThat(log.readLines()).containsExactly("stop", "uninstall")
    }

    @Test
    fun `a failed stop script fails the uninstall without running the uninstall script`() {
        clusterState.runningKits = setOf("mydb")
        val log = File(workingDir, "phases.log")
        writeScript("mydb", "stop", "exit 3")
        writeScript("mydb", "uninstall", "echo uninstall >> \"${log.absolutePath}\"")

        assertThat(command("mydb", "uninstall").call()).isEqualTo(3)

        assertThat(log).doesNotExist()
        assertThat(File(workingDir, "mydb")).isDirectory()
    }
}
