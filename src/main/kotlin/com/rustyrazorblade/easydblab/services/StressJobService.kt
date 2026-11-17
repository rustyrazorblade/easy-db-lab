package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.stress.StressCluster
import com.rustyrazorblade.easydblab.configuration.stress.StressJobManifestBuilder
import com.rustyrazorblade.easydblab.configuration.stress.StressSidecarConfig
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.ImagePullFailedException
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import com.rustyrazorblade.easydblab.providers.aws.pollUntilOrTimeout
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

/**
 * Configuration for starting a stress job.
 *
 * @property jobName Unique name for the job
 * @property image Container image for cassandra-easy-stress
 * @property args Arguments to pass to cassandra-easy-stress
 * @property contactPoints Cassandra contact points
 * @property tags User-provided tags for OTel resource attributes
 * @property promPort Prometheus metrics port for the stress process
 */
data class StressJobConfig(
    val jobName: String,
    val image: String,
    val args: List<String>,
    val contactPoints: String,
    val tags: Map<String, String> = emptyMap(),
    val promPort: Int = Constants.Stress.PROMETHEUS_PORT,
)

/**
 * A stress job whose pod is running: its name, and the alias of the node the pod runs on.
 */
data class StartedStressJob(
    val jobName: String,
    val host: String,
)

/**
 * Service for managing cassandra-easy-stress K8s Jobs.
 *
 * This service encapsulates all K8s operations for stress jobs, providing
 * a high-level interface for starting, stopping, and monitoring stress tests.
 * It delegates to K8sService for actual K8s API interactions.
 */
interface StressJobService {
    /**
     * Starts a stress job on the K8s cluster.
     *
     * @param config The stress job configuration
     * @return Result containing the job name and the node its pod runs on, or failure
     */
    fun startJob(config: StressJobConfig): Result<StartedStressJob>

    /**
     * Stops and deletes a stress job.
     *
     * @param jobName Name of the job to delete
     * @return Result indicating success or failure
     */
    fun stopJob(jobName: String): Result<Unit>

    /**
     * Gets all stress jobs.
     *
     * @return Result containing list of stress jobs
     */
    fun listJobs(): Result<List<KubernetesJob>>

    /**
     * Gets pods for a specific job.
     *
     * @param jobName Name of the job
     * @return Result containing list of pods
     */
    fun getPodsForJob(jobName: String): Result<List<KubernetesPod>>

    /**
     * Gets logs from a pod.
     *
     * @param podName Name of the pod
     * @param tailLines Optional number of lines from the end
     * @return Result containing log content
     */
    fun getPodLogs(
        podName: String,
        tailLines: Int? = null,
    ): Result<String>

    /**
     * Runs a short-lived stress command and returns the output.
     *
     * This creates a Job that runs to completion and captures its output.
     * Used for commands like 'list', 'info', 'fields'.
     *
     * @param image Container image for cassandra-easy-stress
     * @param args Arguments to pass to cassandra-easy-stress
     * @return Result containing the command output
     */
    fun runCommand(
        image: String,
        args: List<String>,
    ): Result<String>
}

/**
 * Default implementation of StressJobService.
 *
 * @param jobPollInterval How long to wait between job-completion polls. Defaults to
 *   [JOB_POLL_INTERVAL_MS] so production timing is unchanged; tests inject [Duration.ZERO].
 * @param podReadyPollInterval How long to wait between polls for a started job's pod to run.
 *   Defaults to [POD_READY_POLL_INTERVAL_MS]; tests inject [Duration.ZERO].
 * @param jobCompletionTimeout How long `runCommand` waits for its Job to finish before it fails with
 *   an `AwsTimeoutException`. Defaults to [JOB_COMPLETION_TIMEOUT_SECONDS] seconds; tests inject a
 *   short one.
 */
class DefaultStressJobService(
    private val k8sService: K8sService,
    private val clusterStateManager: ClusterStateManager,
    private val eventBus: EventBus,
    private val templateService: TemplateService,
    private val jobPollInterval: Duration = Duration.ofMillis(JOB_POLL_INTERVAL_MS),
    private val podReadyPollInterval: Duration = Duration.ofMillis(POD_READY_POLL_INTERVAL_MS),
    private val jobCompletionTimeout: Duration = Duration.ofSeconds(JOB_COMPLETION_TIMEOUT_SECONDS.toLong()),
) : StressJobService {
    private val log = KotlinLogging.logger {}
    private val sidecarConfig = StressSidecarConfig(templateService, k8sService)

    companion object {
        private const val JOB_COMPLETION_TIMEOUT_SECONDS = 30
        private const val JOB_POLL_INTERVAL_MS = 1000L
        private const val POD_READY_MAX_ATTEMPTS = 10
        private const val POD_READY_POLL_INTERVAL_MS = 3000L

        /** Pod phases that end the wait for a started job's pod. */
        private val POD_RUNNING_PHASES = setOf("Running", "Succeeded")
    }

    override fun startJob(config: StressJobConfig): Result<StartedStressJob> =
        runCatching {
            log.info { "Starting stress job: ${config.jobName}" }

            sidecarConfig.ensureConfigMap()

            val job = buildJob(config)

            eventBus.emit(Event.Stress.JobStarting(config.jobName))
            k8sService
                .createJob(Constants.Stress.NAMESPACE, job)
                .getOrThrow()

            waitForPodRunning(config.jobName)
        }

    /**
     * Polls until the job's first pod is Running or Succeeded, and returns the job with the node the
     * pod runs on. No pod yet, another phase, a Failed pod and a failed query are all polled again; if
     * the pod is not running within [POD_READY_MAX_ATTEMPTS] looks, fails with the last look's outcome.
     * A container that cannot pull its image fails at once: it emits [Event.Stress.ImagePullFailed] and
     * throws [ImagePullFailedException], because no retry makes it start.
     */
    private fun waitForPodRunning(jobName: String): StartedStressJob {
        val pods =
            pollUntil(
                operationName = "wait-for-stress-pod-$jobName",
                maxAttempts = POD_READY_MAX_ATTEMPTS,
                interval = podReadyPollInterval,
                done = { found -> found.firstOrNull()?.let { it.status in POD_RUNNING_PHASES || it.imagePullFailure != null } ?: false },
            ) {
                getPodsForJob(jobName).getOrThrow().also { found ->
                    found.firstOrNull()?.let { pod -> check(pod.status != "Failed") { "Pod ${pod.name} failed" } }
                }
            }
        val pod = pods.firstOrNull() ?: error("No pods created yet for job $jobName")
        pod.imagePullFailure?.let { failure ->
            eventBus.emit(
                Event.Stress.ImagePullFailed(
                    podName = pod.name,
                    container = failure.container,
                    image = failure.image,
                    reason = failure.reason,
                    message = failure.message,
                ),
            )
            throw ImagePullFailedException(
                podName = pod.name,
                image = failure.image,
                message = "Pod ${pod.name} cannot pull image ${failure.image} (${failure.reason}): ${failure.message}",
            )
        }
        check(pod.status in POD_RUNNING_PHASES) { "Pod ${pod.name} is ${pod.status}, waiting for Running" }
        eventBus.emit(Event.Stress.PodStatus(pod.name, pod.status))
        return StartedStressJob(jobName = jobName, host = pod.nodeName)
    }

    /** Builds the OTEL_RESOURCE_ATTRIBUTES value from job name and user tags. */
    internal fun buildResourceAttributes(
        jobName: String,
        tags: Map<String, String>,
    ): String = StressJobManifestBuilder.buildResourceAttributes(jobName, tags)

    override fun stopJob(jobName: String): Result<Unit> =
        runCatching {
            log.info { "Stopping stress job: $jobName" }

            // Delete the job
            k8sService
                .deleteJob(Constants.Stress.NAMESPACE, jobName)
                .getOrThrow()

            log.info { "Stopped stress job: $jobName" }
        }

    override fun listJobs(): Result<List<KubernetesJob>> =
        k8sService.getJobsByLabel(
            Constants.Stress.NAMESPACE,
            Constants.Stress.LABEL_KEY,
            Constants.Stress.LABEL_VALUE,
        )

    override fun getPodsForJob(jobName: String): Result<List<KubernetesPod>> = k8sService.getPodsForJob(Constants.Stress.NAMESPACE, jobName)

    override fun getPodLogs(
        podName: String,
        tailLines: Int?,
    ): Result<String> = k8sService.getPodLogs(Constants.Stress.NAMESPACE, podName, tailLines)

    override fun runCommand(
        image: String,
        args: List<String>,
    ): Result<String> =
        runCatching {
            val timestamp = System.currentTimeMillis() / Constants.Time.MILLIS_PER_SECOND
            val jobName = "cmd-$timestamp"

            log.info { "Running stress command: ${args.joinToString(" ")}" }

            val job = buildCommandJob(jobName, image, args)

            k8sService
                .createJob(Constants.Stress.NAMESPACE, job)
                .getOrThrow()

            try {
                waitForJobAndGetLogs(jobName)
            } finally {
                k8sService
                    .deleteJob(Constants.Stress.NAMESPACE, jobName)
                    .onFailure { log.warn { "Failed to clean up command job: $jobName" } }
            }
        }

    /**
     * Waits for a job to complete and returns its logs.
     */
    private fun waitForJobAndGetLogs(jobName: String): String {
        pollUntilOrTimeout(
            what = "stress job $jobName to finish",
            timeout = jobCompletionTimeout,
            interval = jobPollInterval,
            done = { job -> job != null && (job.status == "Completed" || job.status == "Failed") },
        ) {
            k8sService
                .getJobsByLabel(Constants.Stress.NAMESPACE, "job-name", jobName)
                .getOrElse { emptyList() }
                .firstOrNull()
        }

        val pod =
            k8sService
                .getPodsForJob(Constants.Stress.NAMESPACE, jobName)
                .getOrElse { emptyList() }
                .firstOrNull()
                ?: return ""
        return k8sService
            .getPodLogs(Constants.Stress.NAMESPACE, pod.name, null)
            .getOrElse { "" }
    }

    /**
     * Builds the stress Job for [config]: the cluster facts come from one load of the cluster state,
     * the manifest from [StressJobManifestBuilder].
     */
    internal fun buildJob(config: StressJobConfig): Job =
        StressJobManifestBuilder.buildStressJob(config, StressCluster.from(clusterStateManager.load()))

    /** Builds the Job for a short-lived command. */
    internal fun buildCommandJob(
        jobName: String,
        image: String,
        args: List<String>,
    ): Job = StressJobManifestBuilder.buildCommandJob(jobName, image, args, RoleSelectors.from(clusterStateManager.load()).app)
}
