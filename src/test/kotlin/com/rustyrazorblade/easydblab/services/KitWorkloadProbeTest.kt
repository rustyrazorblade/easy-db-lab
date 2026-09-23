package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * The helm-backed branch of [KitWorkloadProbe], and how it reads pod phases. helm runs on the
 * control node over SSH, so [HelmService] is stubbed at that boundary; the phase tests stub
 * [KubernetesService] with pods in each phase. The pod-backed branch's selector and namespace
 * handling runs against K3s in `KitWorkloadProbeIntegrationTest`.
 */
class KitWorkloadProbeTest {
    private val helmService: HelmService = mock()
    private val kubeService: KubernetesService = mock()
    private val probe = KitWorkloadProbe(kubeService, helmService)
    private val controlHost = ClusterHost(publicIp = "", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")

    @Test
    fun `a helm runtime is present when its release exists in the runtime namespace`() {
        whenever(helmService.releaseExists(any(), eq("trino"), eq("analytics"))).thenReturn(true)
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM, release = "trino", namespace = "analytics")

        assertThat(probe.find("trino", runtime, controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "analytics", resources = listOf("helm-release/trino")))
        verifyNoInteractions(kubeService)
    }

    @Test
    fun `a helm runtime without a release name is looked up by the kit name`() {
        whenever(helmService.releaseExists(any(), eq("presto"), eq("default"))).thenReturn(true)
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM)

        assertThat(probe.find("presto", runtime, controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("helm-release/presto")))
    }

    private val helmRuntime = KitRuntime(type = KitRuntime.RuntimeType.HELM, release = "trino", namespace = "analytics")

    private fun releasePods(vararg pods: KubernetesPod) {
        whenever(kubeService.listPodsByLabel(any(), any())).thenReturn(Result.success(emptyList()))
        whenever(kubeService.listPodsByLabel(eq("app.kubernetes.io/instance=trino"), eq("analytics")))
            .thenReturn(Result.success(pods.toList()))
    }

    /**
     * `helm uninstall` returns once the release record is gone, while the release's pods are still
     * terminating for their grace period. A `start` right after would race them, so the stop wait
     * holds until no pod of the release is left either.
     */
    @Test
    fun `the stop wait for a helm runtime holds while pods of the uninstalled release are still terminating`() {
        whenever(helmService.releaseExists(any(), any(), any())).thenReturn(false)
        releasePods(pod("trino-coordinator-0", "Running").copy(namespace = "analytics", terminating = true))
        val impatient = KitWorkloadProbe(kubeService, helmService, pollInterval = Duration.ZERO, maxPolls = 2)

        assertThat(impatient.awaitGone("trino", helmRuntime, controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "analytics", resources = listOf("pod/trino-coordinator-0")))
    }

    @Test
    fun `the stop wait for a helm runtime ends once the release and its pods are both gone`() {
        whenever(helmService.releaseExists(any(), any(), any())).thenReturn(true, false, false)
        whenever(kubeService.listPodsByLabel(eq("app.kubernetes.io/instance=trino"), eq("analytics")))
            .thenReturn(Result.success(listOf(pod("trino-worker-0", "Running").copy(terminating = true))))
            .thenReturn(Result.success(emptyList()))
        val waiting = KitWorkloadProbe(kubeService, helmService, pollInterval = Duration.ZERO, maxPolls = 5)

        assertThat(waiting.awaitGone("trino", helmRuntime, controlHost).getOrThrow()).isEqualTo(WorkloadPresence.Absent)
        verify(helmService, times(3)).releaseExists(any(), any(), any())
    }

    private fun pod(
        name: String,
        phase: String,
    ) = KubernetesPod(namespace = "default", name = name, status = phase, ready = "0/1", restarts = 0, age = Duration.ZERO)

    private val runPods = KitRuntime(type = KitRuntime.RuntimeType.PODS, selector = "easydblab/kit=sysbench-tidb")

    /**
     * A kit that runs its workload as bare pods (sysbench) leaves each finished run's pod behind in
     * Succeeded or Failed. Such a pod is not running, so it must neither refuse the next `start`
     * nor hold `stop` waiting for it to leave.
     */
    @Test
    fun `a pod that has finished is not a running workload`() {
        whenever(kubeService.listPodsByLabel(eq("easydblab/kit=sysbench-tidb"), eq("default")))
            .thenReturn(Result.success(listOf(pod("run-1", "Succeeded"), pod("run-2", "Failed"), pod("run-3", "Running"))))

        assertThat(probe.find("sysbench-tidb", runPods, controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/run-3")))
    }

    @Test
    fun `only finished pods means the workload is absent, for start and for the stop wait`() {
        whenever(kubeService.listPodsByLabel(any(), any()))
            .thenReturn(Result.success(listOf(pod("run-1", "Succeeded"), pod("run-2", "Failed"))))
        val impatient = KitWorkloadProbe(kubeService, helmService, pollInterval = Duration.ZERO, maxPolls = 1)

        assertThat(probe.find("sysbench-tidb", runPods, controlHost).getOrThrow()).isEqualTo(WorkloadPresence.Absent)
        assertThat(impatient.awaitGone("sysbench-tidb", runPods, controlHost).getOrThrow()).isEqualTo(WorkloadPresence.Absent)
    }

    @Test
    fun `a pending pod is part of the running workload`() {
        whenever(kubeService.listPodsByLabel(any(), any())).thenReturn(Result.success(listOf(pod("run-1", "Pending"))))

        assertThat(probe.find("sysbench-tidb", runPods, controlHost).getOrThrow())
            .isEqualTo(WorkloadPresence.Present(namespace = "default", resources = listOf("pod/run-1")))
    }

    /**
     * The stop wait lasts minutes over a SOCKS tunnel; one dropped API call inside it must not
     * throw away the whole wait when the next look succeeds.
     */
    @Test
    fun `a transient lookup failure during the stop wait is retried within the poll budget`() {
        whenever(kubeService.listPodsByLabel(any(), any()))
            .thenReturn(Result.failure(IllegalStateException("connection reset")))
            .thenReturn(Result.success(listOf(pod("run-1", "Running"))))
            .thenReturn(Result.success(emptyList()))
        val waiting = KitWorkloadProbe(kubeService, helmService, pollInterval = Duration.ZERO, maxPolls = 3)

        assertThat(waiting.awaitGone("sysbench-tidb", runPods, controlHost).getOrThrow()).isEqualTo(WorkloadPresence.Absent)
    }

    @Test
    fun `the stop wait fails when its last look still throws`() {
        whenever(kubeService.listPodsByLabel(any(), any()))
            .thenReturn(Result.success(listOf(pod("run-1", "Running"))))
            .thenReturn(Result.failure(IllegalStateException("connection reset")))
        val waiting = KitWorkloadProbe(kubeService, helmService, pollInterval = Duration.ZERO, maxPolls = 2)

        val result = waiting.awaitGone("sysbench-tidb", runPods, controlHost)

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java).hasMessage("connection reset")
        verify(kubeService, times(2)).listPodsByLabel(any(), any())
    }

    @Test
    fun `a failed helm lookup fails the result instead of reporting the kit absent`() {
        whenever(helmService.releaseExists(any(), any(), any())).thenThrow(IllegalStateException("ssh down"))
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM, release = "trino")

        assertThat(probe.find("trino", runtime, controlHost).isFailure).isTrue()
    }
}
