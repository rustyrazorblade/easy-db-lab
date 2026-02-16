package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

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
     * @param controlHost The control node running K3s
     * @param jobName Unique name for the job
     * @param image Container image for cassandra-easy-stress
     * @param args Arguments to pass to cassandra-easy-stress
     * @param contactPoints Cassandra contact points
     * @return Result containing the created job name or failure
     */
    fun startJob(
        controlHost: ClusterHost,
        jobName: String,
        image: String,
        args: List<String>,
        contactPoints: String,
    ): Result<String>

    /**
     * Stops and deletes a stress job.
     *
     * @param controlHost The control node running K3s
     * @param jobName Name of the job to delete
     * @return Result indicating success or failure
     */
    fun stopJob(
        controlHost: ClusterHost,
        jobName: String,
    ): Result<Unit>

    /**
     * Gets all stress jobs.
     *
     * @param controlHost The control node running K3s
     * @return Result containing list of stress jobs
     */
    fun listJobs(controlHost: ClusterHost): Result<List<KubernetesJob>>

    /**
     * Gets pods for a specific job.
     *
     * @param controlHost The control node running K3s
     * @param jobName Name of the job
     * @return Result containing list of pods
     */
    fun getPodsForJob(
        controlHost: ClusterHost,
        jobName: String,
    ): Result<List<KubernetesPod>>

    /**
     * Gets logs from a pod.
     *
     * @param controlHost The control node running K3s
     * @param podName Name of the pod
     * @param tailLines Optional number of lines from the end
     * @return Result containing log content
     */
    fun getPodLogs(
        controlHost: ClusterHost,
        podName: String,
        tailLines: Int? = null,
    ): Result<String>

    /**
     * Runs a short-lived stress command and returns the output.
     *
     * This creates a Job that runs to completion and captures its output.
     * Used for commands like 'list', 'info', 'fields'.
     *
     * @param controlHost The control node running K3s
     * @param image Container image for cassandra-easy-stress
     * @param args Arguments to pass to cassandra-easy-stress
     * @return Result containing the command output
     */
    fun runCommand(
        controlHost: ClusterHost,
        image: String,
        args: List<String>,
    ): Result<String>
}

/**
 * Default implementation of StressJobService.
 */
class DefaultStressJobService(
    private val k8sService: K8sService,
    private val outputHandler: OutputHandler,
) : StressJobService {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val JOB_COMPLETION_TIMEOUT_SECONDS = 30
        private const val JOB_POLL_INTERVAL_MS = 1000L
        private const val TTL_STRESS_JOB_SECONDS = 86400
        private const val TTL_COMMAND_JOB_SECONDS = 300
    }

    override fun startJob(
        controlHost: ClusterHost,
        jobName: String,
        image: String,
        args: List<String>,
        contactPoints: String,
    ): Result<String> =
        runCatching {
            log.info { "Starting stress job: $jobName" }

            val job =
                buildJob(
                    jobName = jobName,
                    image = image,
                    contactPoints = contactPoints,
                    args = args,
                )

            outputHandler.handleMessage("Starting stress job: $jobName")
            k8sService
                .createJob(controlHost, Constants.Stress.NAMESPACE, job)
                .getOrThrow()
        }

    override fun stopJob(
        controlHost: ClusterHost,
        jobName: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Stopping stress job: $jobName" }

            // Delete the job
            k8sService
                .deleteJob(controlHost, Constants.Stress.NAMESPACE, jobName)
                .getOrThrow()

            log.info { "Stopped stress job: $jobName" }
        }

    override fun listJobs(controlHost: ClusterHost): Result<List<KubernetesJob>> =
        k8sService.getJobsByLabel(
            controlHost,
            Constants.Stress.NAMESPACE,
            Constants.Stress.LABEL_KEY,
            Constants.Stress.LABEL_VALUE,
        )

    override fun getPodsForJob(
        controlHost: ClusterHost,
        jobName: String,
    ): Result<List<KubernetesPod>> = k8sService.getPodsForJob(controlHost, Constants.Stress.NAMESPACE, jobName)

    override fun getPodLogs(
        controlHost: ClusterHost,
        podName: String,
        tailLines: Int?,
    ): Result<String> = k8sService.getPodLogs(controlHost, Constants.Stress.NAMESPACE, podName, tailLines)

    override fun runCommand(
        controlHost: ClusterHost,
        image: String,
        args: List<String>,
    ): Result<String> =
        runCatching {
            val timestamp = System.currentTimeMillis() / Constants.Time.MILLIS_PER_SECOND
            val jobName = "${Constants.Stress.JOB_PREFIX}-cmd-$timestamp"

            log.info { "Running stress command: ${args.joinToString(" ")}" }

            val job = buildCommandJob(jobName, image, args)

            k8sService
                .createJob(controlHost, Constants.Stress.NAMESPACE, job)
                .getOrThrow()

            // Wait for job completion
            val output = waitForJobAndGetLogs(controlHost, jobName)

            // Clean up the job
            k8sService
                .deleteJob(controlHost, Constants.Stress.NAMESPACE, jobName)
                .onFailure { log.warn { "Failed to clean up command job: $jobName" } }

            output
        }

    /**
     * Waits for a job to complete and returns its logs.
     */
    private fun waitForJobAndGetLogs(
        controlHost: ClusterHost,
        jobName: String,
    ): String {
        val startTime = System.currentTimeMillis()
        val timeoutMs = JOB_COMPLETION_TIMEOUT_SECONDS * Constants.Time.MILLIS_PER_SECOND

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val jobs =
                k8sService
                    .getJobsByLabel(
                        controlHost,
                        Constants.Stress.NAMESPACE,
                        "job-name",
                        jobName,
                    ).getOrElse { emptyList() }

            val job = jobs.firstOrNull()
            if (job != null && (job.status == "Completed" || job.status == "Failed")) {
                // Get logs from the pod
                val pods =
                    k8sService
                        .getPodsForJob(controlHost, Constants.Stress.NAMESPACE, jobName)
                        .getOrElse { emptyList() }

                val pod = pods.firstOrNull()
                if (pod != null) {
                    return k8sService
                        .getPodLogs(controlHost, Constants.Stress.NAMESPACE, pod.name, null)
                        .getOrElse { "" }
                }
                break
            }

            Thread.sleep(JOB_POLL_INTERVAL_MS)
        }

        return ""
    }

    /**
     * Builds a Kubernetes Job for a stress job with OTel sidecar.
     */
    internal fun buildJob(
        jobName: String,
        image: String,
        contactPoints: String,
        args: List<String>,
    ): Job {
        val labels =
            mapOf(
                Constants.Stress.LABEL_KEY to Constants.Stress.LABEL_VALUE,
                "job-name" to jobName,
            )

        val stressContainer =
            ContainerBuilder()
                .withName("stress")
                .withImage(image)
                .withArgs(args)
                .withEnv(
                    EnvVarBuilder()
                        .withName("CASSANDRA_CONTACT_POINTS")
                        .withValue(contactPoints)
                        .build(),
                    EnvVarBuilder()
                        .withName("CASSANDRA_PORT")
                        .withValue(Constants.Stress.DEFAULT_CASSANDRA_PORT.toString())
                        .build(),
                ).build()

        val otelSidecar =
            ContainerBuilder()
                .withName("otel-sidecar")
                .withImage("otel/opentelemetry-collector-contrib:latest")
                .withArgs("--config=/etc/otel/otel-stress-sidecar-config.yaml")
                .withEnv(
                    EnvVarBuilder()
                        .withName("K8S_NODE_NAME")
                        .withNewValueFrom()
                        .withNewFieldRef()
                        .withFieldPath("spec.nodeName")
                        .endFieldRef()
                        .endValueFrom()
                        .build(),
                    EnvVarBuilder()
                        .withName("HOST_IP")
                        .withNewValueFrom()
                        .withNewFieldRef()
                        .withFieldPath("status.hostIP")
                        .endFieldRef()
                        .endValueFrom()
                        .build(),
                    EnvVarBuilder()
                        .withName("CLUSTER_NAME")
                        .withNewValueFrom()
                        .withNewConfigMapKeyRef()
                        .withName("cluster-config")
                        .withKey("cluster_name")
                        .endConfigMapKeyRef()
                        .endValueFrom()
                        .build(),
                    EnvVarBuilder()
                        .withName("GOMEMLIMIT")
                        .withValue("64MiB")
                        .build(),
                ).withResources(
                    ResourceRequirementsBuilder()
                        .addToRequests("memory", Quantity("32Mi"))
                        .addToRequests("cpu", Quantity("25m"))
                        .addToLimits("memory", Quantity("64Mi"))
                        .build(),
                ).withVolumeMounts(
                    VolumeMountBuilder()
                        .withName("otel-sidecar-config")
                        .withMountPath("/etc/otel")
                        .withReadOnly(true)
                        .build(),
                ).build()

        return JobBuilder()
            .withNewMetadata()
            .withName(jobName)
            .withNamespace(Constants.Stress.NAMESPACE)
            .withLabels<String, String>(labels)
            .endMetadata()
            .withNewSpec()
            .withBackoffLimit(0)
            .withTtlSecondsAfterFinished(TTL_STRESS_JOB_SECONDS)
            .withNewTemplate()
            .withNewMetadata()
            .withLabels<String, String>(labels)
            .endMetadata()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withNodeSelector<String, String>(mapOf("type" to ServerType.Stress.serverType))
            .withContainers(stressContainer, otelSidecar)
            .withVolumes(
                VolumeBuilder()
                    .withName("otel-sidecar-config")
                    .withNewConfigMap()
                    .withName("otel-stress-sidecar-config")
                    .endConfigMap()
                    .build(),
            ).endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    /**
     * Builds a simple Kubernetes Job for short-lived commands.
     */
    internal fun buildCommandJob(
        jobName: String,
        image: String,
        args: List<String>,
    ): Job {
        val labels =
            mapOf(
                Constants.Stress.LABEL_KEY to Constants.Stress.LABEL_VALUE,
                "job-name" to jobName,
            )

        val stressContainer =
            ContainerBuilder()
                .withName("stress")
                .withImage(image)
                .withArgs(args)
                .build()

        return JobBuilder()
            .withNewMetadata()
            .withName(jobName)
            .withNamespace(Constants.Stress.NAMESPACE)
            .withLabels<String, String>(labels)
            .endMetadata()
            .withNewSpec()
            .withBackoffLimit(0)
            .withTtlSecondsAfterFinished(TTL_COMMAND_JOB_SECONDS)
            .withNewTemplate()
            .withNewMetadata()
            .withLabels<String, String>(labels)
            .endMetadata()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withNodeSelector<String, String>(mapOf("type" to ServerType.Stress.serverType))
            .withContainers(stressContainer)
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }
}
