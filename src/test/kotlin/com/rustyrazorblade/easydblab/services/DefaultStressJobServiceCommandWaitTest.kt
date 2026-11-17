package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.AwsTimeoutException
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import io.fabric8.kubernetes.api.model.batch.v1.Job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * How `runCommand` waits for its short-lived Job: it polls the Job's status until it is Completed or
 * Failed, treating a failed query as one more look, then returns the pod's logs and deletes the Job.
 * Runs with a zero poll interval.
 */
class DefaultStressJobServiceCommandWaitTest : BaseKoinTest() {
    private val k8sService: K8sService = mock()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mock { on { load() } doReturn ClusterState(name = "lab") } }
                single { TemplateService(get(), get()) }
            },
        )

    private fun service(jobCompletionTimeout: Duration = Duration.ofSeconds(5)) =
        DefaultStressJobService(
            k8sService = k8sService,
            clusterStateManager = getKoin().get(),
            eventBus = EventBus(),
            templateService = getKoin().get(),
            jobPollInterval = Duration.ZERO,
            jobCompletionTimeout = jobCompletionTimeout,
        )

    private fun job(status: String) = KubernetesJob("stress", "cmd", status, "0/1", Duration.ZERO)

    private val pod = KubernetesPod("stress", "cmd-pod", "Succeeded", "0/1", 0, Duration.ZERO, nodeName = "app0")

    @BeforeEach
    fun stubCreationAndLogs() {
        whenever(k8sService.createJob(any(), any<Job>())).thenReturn(Result.success("cmd"))
        whenever(k8sService.deleteJob(any(), any())).thenReturn(Result.success(Unit))
        whenever(k8sService.getPodsForJob(any(), any())).thenReturn(Result.success(listOf(pod)))
        whenever(k8sService.getPodLogs(any(), eq("cmd-pod"), anyOrNull())).thenReturn(Result.success("the output"))
    }

    @Test
    fun `returns the logs once the job completes, through a failed query and a running job`() {
        whenever(k8sService.getJobsByLabel(any(), any(), any())).thenReturn(
            Result.failure(IllegalStateException("connection reset")),
            Result.success(listOf(job("Running"))),
            Result.success(listOf(job("Completed"))),
        )

        val result = service().runCommand("image", listOf("--version"))

        assertThat(result.getOrThrow()).isEqualTo("the output")
        verify(k8sService, times(3)).getJobsByLabel(any(), any(), any())
        verify(k8sService).deleteJob(any(), any())
    }

    @Test
    fun `a job that never finishes fails with AwsTimeoutException and is still deleted`() {
        whenever(k8sService.getJobsByLabel(any(), any(), any())).thenReturn(Result.success(listOf(job("Running"))))

        val result = service(jobCompletionTimeout = Duration.ofMillis(50)).runCommand("image", listOf("--version"))

        assertThat(result.exceptionOrNull())
            .isInstanceOf(AwsTimeoutException::class.java)
            .hasMessageMatching("Timeout waiting for stress job cmd-\\d+ to finish after 50ms")
        verify(k8sService).deleteJob(any(), any())
    }

    @Test
    fun `a failed job also ends the wait`() {
        whenever(k8sService.getJobsByLabel(any(), any(), any())).thenReturn(Result.success(listOf(job("Failed"))))

        assertThat(service().runCommand("image", listOf("--version")).getOrThrow()).isEqualTo("the output")
    }
}
