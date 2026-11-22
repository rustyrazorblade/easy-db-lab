package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Duration

/**
 * A collision-checked kit refuses `start` while its workload is already in the cluster, found
 * through the kit's runtime declaration (typed-install-steps: "Top-level boolean applies to
 * start phase only"), and `stop` waits for that workload to leave. The cluster query itself
 * runs against K3s in KitWorkloadProbeIntegrationTest; here it is stubbed at the Kubernetes API
 * boundary.
 */
class KitRunnerCommandCollisionCheckTest : KitRunnerCommandTestBase() {
    private val runningPod =
        KubernetesPod(namespace = "db", name = "mydb-0", status = "Running", ready = "1/1", restarts = 0, age = Duration.ofMinutes(5))

    private val collisionCheckedHeader =
        """
        name: mydb
        collision-check: true
        runtime:
          type: pods
          selector: "easydblab/kit=mydb"
          namespace: db
        """.trimIndent()

    private fun writeCollisionCheckedKit(collisionCheck: Boolean = true) =
        writeKitYaml(
            "mydb",
            collisionCheckedHeader.replace("collision-check: true", "collision-check: $collisionCheck") + "\n" +
                """
                start:
                  - type: shell
                    script: echo start
                stop:
                  - type: shell
                    script: echo stop
                """.trimIndent(),
        )

    private fun podsInCluster(vararg pods: KubernetesPod) {
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db")).thenReturn(Result.success(pods.toList()))
    }

    @Test
    fun `start refuses a kit that is already running, runs no step, and exits non-zero`() {
        writeCollisionCheckedKit()
        podsInCluster(runningPod)

        var exitCode = 0
        val events = captureEvents { exitCode = command("mydb", "start").call() }

        assertThat(exitCode).isNotEqualTo(0)
        verify(mockWorkloadStepExecutor, never()).execute(any(), any(), any())
        val collision = events.filterIsInstance<Event.Kit.CollisionDetected>().single()
        assertThat(collision).isEqualTo(
            Event.Kit.CollisionDetected(kit = "mydb", phase = "start", namespace = "db", resources = listOf("pod/mydb-0")),
        )
        assertThat(collision.isError()).isTrue()
        assertThat(collision.toDisplayString()).startsWith("Error:").contains("mydb", "pod/mydb-0", "stop")
        verify(mockClusterStateManager, never()).addRunningWorkload(any())
    }

    @Test
    fun `start refuses a kit whose pods are still terminating, naming them`() {
        writeCollisionCheckedKit()
        podsInCluster(runningPod.copy(terminating = true))

        var exitCode = 0
        val events = captureEvents { exitCode = command("mydb", "start").call() }

        assertThat(exitCode).isNotEqualTo(0)
        verify(mockWorkloadStepExecutor, never()).execute(any(), any(), any())
        assertThat(events.filterIsInstance<Event.Kit.CollisionDetected>().single().toDisplayString())
            .startsWith("Error:")
            .contains("mydb", "pod/mydb-0")
    }

    @Test
    fun `start runs when nothing of the kit is in the cluster`() {
        writeCollisionCheckedKit()
        podsInCluster()

        val exitCode = command("mydb", "start").call()

        assertThat(exitCode).isEqualTo(0)
        verify(mockWorkloadStepExecutor).execute(any(), any(), any())
    }

    @Test
    fun `start fails without running any step when the cluster cannot be queried`() {
        writeCollisionCheckedKit()
        whenever(mockKubeService.listPodsByLabel(any(), any())).thenReturn(Result.failure(IllegalStateException("api down")))

        assertThatThrownBy { command("mydb", "start").call() }.hasMessageContaining("api down")
        verify(mockWorkloadStepExecutor, never()).execute(any(), any(), any())
    }

    @Test
    fun `stop is not refused while the kit is running`() {
        writeCollisionCheckedKit()
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db"))
            .thenReturn(Result.success(listOf(runningPod)), Result.success(emptyList()))

        assertThat(command("mydb", "stop").call()).isEqualTo(0)
        verify(mockWorkloadStepExecutor).execute(any(), any(), any())
    }

    /**
     * Deleting a StatefulSet, Deployment or operator CR returns before its pods are even marked
     * for deletion, so a `start` right after `stop` would find them and be refused. Stop waits.
     */
    @Test
    fun `stop waits until every pod of the kit is gone, terminating ones included`() {
        writeCollisionCheckedKit()
        val terminatingPod = runningPod.copy(terminating = true)
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db")).thenReturn(
            Result.success(listOf(runningPod)),
            Result.success(listOf(terminatingPod)),
            Result.success(emptyList()),
        )

        var exitCode = -1
        val events = captureEvents { exitCode = command("mydb", "stop").call() }

        assertThat(exitCode).isEqualTo(0)
        verify(mockKubeService, times(3)).listPodsByLabel("easydblab/kit=mydb", "db")
        assertThat(events.filterIsInstance<Event.Kit.ScriptFinished>().single().exitCode).isEqualTo(0)
        verify(mockClusterStateManager).removeRunningWorkload("mydb")
    }

    @Test
    fun `stop fails with an error naming what is left when the kit's pods outlive the wait`() {
        writeCollisionCheckedKit()
        podsInCluster(runningPod)

        var exitCode = 0
        val events = captureEvents { exitCode = command("mydb", "stop").call() }

        assertThat(exitCode).isNotEqualTo(0)
        verify(mockKubeService, times(STOP_WAIT_POLLS)).listPodsByLabel("easydblab/kit=mydb", "db")
        val incomplete = events.filterIsInstance<Event.Kit.StopIncomplete>().single()
        assertThat(incomplete).isEqualTo(Event.Kit.StopIncomplete(kit = "mydb", namespace = "db", resources = listOf("pod/mydb-0")))
        assertThat(incomplete.isError()).isTrue()
        assertThat(incomplete.toDisplayString()).startsWith("Error:").contains("mydb", "pod/mydb-0", "stop")
        assertThat(events.filterIsInstance<Event.Kit.ScriptFinished>().single().exitCode).isNotEqualTo(0)
        verify(mockClusterStateManager, never()).removeRunningWorkload(any())
    }

    /**
     * The stop steps already ran, so a cluster that cannot be queried during the wait must not
     * escape as an exception with no ScriptFinished: the stop is reported unverified and failed.
     */
    @Test
    fun `stop reports an unverified stop and fails when the cluster cannot be queried during the wait`() {
        writeCollisionCheckedKit()
        whenever(mockKubeService.listPodsByLabel(any(), any())).thenReturn(Result.failure(IllegalStateException("api down")))

        assertStopUnverified()
    }

    @Test
    fun `a script-driven stop reports an unverified stop and fails when the cluster cannot be queried during the wait`() {
        writeKitYaml("mydb", collisionCheckedHeader)
        writeScript("mydb", "stop", "exit 0")
        whenever(mockKubeService.listPodsByLabel(any(), any())).thenReturn(Result.failure(IllegalStateException("api down")))

        assertStopUnverified()
    }

    private fun assertStopUnverified() {
        var exitCode = 0
        val events = captureEvents { exitCode = command("mydb", "stop").call() }

        assertThat(exitCode).isNotEqualTo(0)
        val unverified = events.filterIsInstance<Event.Kit.StopUnverified>().single()
        assertThat(unverified).isEqualTo(Event.Kit.StopUnverified(kit = "mydb", reason = "api down"))
        assertThat(unverified.isError()).isTrue()
        assertThat(unverified.toDisplayString()).startsWith("Error:").contains("mydb", "api down", "stop")
        assertThat(events.filterIsInstance<Event.Kit.ScriptFinished>().single().exitCode).isNotEqualTo(0)
        verify(mockClusterStateManager, never()).removeRunningWorkload(any())
    }

    /**
     * Presto and Trino are not collision-checked, and their stop only scales their Deployments to
     * zero, which returns while the pods are still terminating. Stop waits for any kit that
     * declares a runtime, so `presto stop` returns only once its pods are gone.
     */
    @Test
    fun `stop waits for the workload to leave even when the kit's start is not collision-checked`() {
        writeCollisionCheckedKit(collisionCheck = false)
        whenever(mockKubeService.listPodsByLabel("easydblab/kit=mydb", "db"))
            .thenReturn(Result.success(listOf(runningPod.copy(terminating = true))), Result.success(emptyList()))

        assertThat(command("mydb", "stop").call()).isEqualTo(0)
        verify(mockKubeService, times(2)).listPodsByLabel("easydblab/kit=mydb", "db")
    }

    @Test
    fun `an unguarded kit's stop fails naming what is left when its pods outlive the wait`() {
        writeCollisionCheckedKit(collisionCheck = false)
        podsInCluster(runningPod)

        var exitCode = 0
        val events = captureEvents { exitCode = command("mydb", "stop").call() }

        assertThat(exitCode).isNotEqualTo(0)
        assertThat(events.filterIsInstance<Event.Kit.StopIncomplete>().single().resources).containsExactly("pod/mydb-0")
    }

    @Test
    fun `stop of a kit that declares no runtime does not wait on the cluster`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            stop:
              - type: shell
                script: echo stop
            """.trimIndent(),
        )

        assertThat(command("mydb", "stop").call()).isEqualTo(0)
        verifyNoInteractions(mockKubeService)
    }

    @Test
    fun `a failed stop does not wait on the cluster`() {
        writeCollisionCheckedKit()
        whenever(mockWorkloadStepExecutor.execute(any(), any(), any())).thenReturn(Result.failure(IllegalStateException("boom")))

        assertThat(command("mydb", "stop").call()).isEqualTo(Constants.ExitCodes.ERROR)
        verifyNoInteractions(mockKubeService)
    }

    @Test
    fun `a kit that collision-checks only install does not guard start`() {
        writeKitYaml(
            "mydb",
            collisionCheckedHeader.replace("collision-check: true", "collision-check: {install: true, start: false}") + "\n" +
                """
                start:
                  - type: shell
                    script: echo start
                stop:
                  - type: shell
                    script: echo stop
                """.trimIndent(),
        )
        podsInCluster(runningPod)

        assertThat(command("mydb", "start").call()).isEqualTo(0)
        verifyNoInteractions(mockKubeService)
    }

    @Test
    fun `a kit that collision-checks start through a phase map is guarded`() {
        writeKitYaml(
            "mydb",
            collisionCheckedHeader.replace("collision-check: true", "collision-check: {start: true, install: false}") + "\n" +
                """
                start:
                  - type: shell
                    script: echo start
                """.trimIndent(),
        )
        podsInCluster(runningPod)

        assertThat(command("mydb", "start").call()).isNotEqualTo(0)
        verify(mockWorkloadStepExecutor, never()).execute(any(), any(), any())
    }

    @Test
    fun `start of a kit without collision-check does not look in the cluster`() {
        writeCollisionCheckedKit(collisionCheck = false)

        assertThat(command("mydb", "start").call()).isEqualTo(0)
        verifyNoInteractions(mockKubeService)
    }

    @Test
    fun `a script-driven start is guarded too`() {
        writeKitYaml("mydb", collisionCheckedHeader)
        val marker = File(workingDir, "script-ran")
        writeScript("mydb", "start", "touch ${marker.absolutePath}")
        podsInCluster(runningPod)

        assertThat(command("mydb", "start").call()).isNotEqualTo(0)
        assertThat(marker).doesNotExist()
    }
}
