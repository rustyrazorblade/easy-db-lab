package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.StressJobOutcome
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [Fabric8StressJobLister] against the Fabric8 Kubernetes mock server: it lists only the stress
 * jobs, reads each one's state and duration, and takes the host from its pod's node.
 */
@EnableKubernetesMockClient(crud = true)
class Fabric8StressJobListerIntegrationTest {
    lateinit var server: KubernetesMockServer

    private val stressLabels =
        mapOf(
            Constants.Stress.LABEL_KEY to Constants.Stress.LABEL_VALUE,
            Constants.Stress.ROLE_LABEL_KEY to Constants.Stress.ROLE_RUN,
        )

    private val commandLabels =
        mapOf(
            Constants.Stress.LABEL_KEY to Constants.Stress.LABEL_VALUE,
            Constants.Stress.ROLE_LABEL_KEY to Constants.Stress.ROLE_COMMAND,
        )

    private fun createJob(
        name: String,
        labels: Map<String, String>,
        finished: String?,
    ) {
        val builder =
            JobBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(Constants.Stress.NAMESPACE)
                .withLabels<String, String>(labels + ("job-name" to name))
                .endMetadata()
                .withNewStatus()
                .withStartTime("2026-11-02T10:15:36Z")
                .endStatus()
        val job =
            when (finished) {
                null -> builder.editStatus().withActive(1).endStatus()
                else ->
                    builder
                        .editStatus()
                        .withCompletionTime("2026-11-02T10:25:41Z")
                        .addNewCondition()
                        .withType(finished)
                        .withStatus("True")
                        .withLastTransitionTime("2026-11-02T10:25:41Z")
                        .endCondition()
                        .endStatus()
            }.build()
        server.createClient().use {
            it
                .batch()
                .v1()
                .jobs()
                .inNamespace(Constants.Stress.NAMESPACE)
                .resource(job)
                .create()
        }
    }

    private fun createPod(
        job: String,
        node: String,
    ) {
        val pod =
            PodBuilder()
                .withNewMetadata()
                .withName("$job-abc")
                .withNamespace(Constants.Stress.NAMESPACE)
                .withLabels<String, String>(stressLabels + ("job-name" to job))
                .endMetadata()
                .withNewSpec()
                .withNodeName(node)
                .endSpec()
                .build()
        server.createClient().use {
            it
                .pods()
                .inNamespace(Constants.Stress.NAMESPACE)
                .resource(pod)
                .create()
        }
    }

    @Test
    fun `lists the stress jobs with their state, duration and host, and leaves other jobs out`() {
        createJob("stress-running", stressLabels, finished = null)
        createPod("stress-running", "app0")
        createJob("stress-done", stressLabels, finished = "Complete")
        createPod("stress-done", "app1")
        createJob("other-job", mapOf("app" to "something-else"), finished = "Complete")
        createJob("cmd-1", commandLabels, finished = null)

        val jobs = lister().list(runningCluster(), ObservabilityRoute.Direct)

        assertThat(jobs).containsExactlyInAnyOrder(
            ListedStressJob.Running("stress-running", "app0"),
            ListedStressJob.Finished("stress-done", "app1", StressJobOutcome.SUCCEEDED, 605),
        )
    }

    @Test
    fun `a stress command job that runs and then finishes is never reported as completed`() {
        val watcher = StressJobWatcher(lister())
        createJob("cmd-1", commandLabels, finished = null)

        val whileRunning = watcher.completed(runningCluster(), ObservabilityRoute.Direct)
        server.createClient().use { client ->
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(Constants.Stress.NAMESPACE)
                .withName("cmd-1")
                .delete()
        }
        createJob("cmd-1", commandLabels, finished = "Complete")
        val afterFinish = watcher.completed(runningCluster(), ObservabilityRoute.Direct)

        assertThat(whileRunning).isEmpty()
        assertThat(afterFinish).isEmpty()
    }

    private fun lister() = Fabric8StressJobLister { server.createClient().configuration }
}
