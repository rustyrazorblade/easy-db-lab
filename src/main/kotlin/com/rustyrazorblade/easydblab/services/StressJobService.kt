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
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig

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
        tags: Map<String, String> = emptyMap(),
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
        private const val POD_READY_MAX_ATTEMPTS = 10
        private const val POD_READY_POLL_INTERVAL_MS = 3000L
        private const val SIDECAR_CONFIG_MAP_NAME = "otel-stress-sidecar-config"
        private const val SIDECAR_CONFIG_FILE_NAME = "otel-stress-sidecar-config.yaml"

        internal val SIDECAR_OTEL_CONFIG =
            """
            |receivers:
            |  prometheus:
            |    config:
            |      scrape_configs:
            |        - job_name: 'cassandra-easy-stress'
            |          scrape_interval: 5s
            |          static_configs:
            |            - targets: ['localhost:9500']
            |          relabel_configs:
            |            - target_label: instance
            |              replacement: '${'$'}{env:K8S_NODE_NAME}:9500'
            |            - target_label: cluster
            |              replacement: '${'$'}{env:CLUSTER_NAME}'
            |
            |processors:
            |  batch:
            |    timeout: 10s
            |  resourcedetection:
            |    detectors: [env]
            |    timeout: 2s
            |    override: false
            |
            |exporters:
            |  otlp:
            |    endpoint: ${'$'}{env:HOST_IP}:4317
            |    tls:
            |      insecure: true
            |
            |service:
            |  pipelines:
            |    metrics:
            |      receivers: [prometheus]
            |      processors: [resourcedetection, batch]
            |      exporters: [otlp]
            """.trimMargin()
    }

    override fun startJob(
        controlHost: ClusterHost,
        jobName: String,
        image: String,
        args: List<String>,
        contactPoints: String,
        tags: Map<String, String>,
    ): Result<String> =
        runCatching {
            log.info { "Starting stress job: $jobName" }

            ensureSidecarConfigMap(controlHost)

            val job =
                buildJob(
                    jobName = jobName,
                    image = image,
                    contactPoints = contactPoints,
                    args = args,
                    tags = tags,
                )

            outputHandler.handleMessage("Starting stress job: $jobName")
            k8sService
                .createJob(controlHost, Constants.Stress.NAMESPACE, job)
                .getOrThrow()

            waitForPodRunning(controlHost, jobName)
        }

    /**
     * Polls until at least one pod for the job is Running or Succeeded.
     * Throws if no pod reaches a running state within 30 seconds.
     */
    private fun waitForPodRunning(
        controlHost: ClusterHost,
        jobName: String,
    ): String {
        val retryConfig =
            RetryConfig
                .custom<String>()
                .maxAttempts(POD_READY_MAX_ATTEMPTS)
                .intervalFunction { _ -> POD_READY_POLL_INTERVAL_MS }
                .retryOnException { true }
                .build()
        val retry = Retry.of("wait-for-stress-pod-$jobName", retryConfig)

        return Retry
            .decorateSupplier(retry) {
                val pods = getPodsForJob(controlHost, jobName).getOrThrow()
                if (pods.isEmpty()) {
                    error("No pods created yet for job $jobName")
                }
                val pod = pods.first()
                when (pod.status) {
                    "Running", "Succeeded" -> {
                        outputHandler.handleMessage("Pod ${pod.name} is ${pod.status}")
                        jobName
                    }
                    "Failed" -> throw IllegalStateException("Pod ${pod.name} failed")
                    else -> error("Pod ${pod.name} is ${pod.status}, waiting for Running")
                }
            }.get()
    }

    /**
     * Ensures the OTel sidecar ConfigMap exists in the stress namespace.
     */
    private fun ensureSidecarConfigMap(controlHost: ClusterHost) {
        k8sService
            .createConfigMap(
                controlHost = controlHost,
                namespace = Constants.Stress.NAMESPACE,
                name = SIDECAR_CONFIG_MAP_NAME,
                data = mapOf(SIDECAR_CONFIG_FILE_NAME to SIDECAR_OTEL_CONFIG),
                labels = mapOf("app.kubernetes.io/name" to "otel-stress-sidecar"),
            ).getOrThrow()
    }

    /**
     * Builds the OTEL_RESOURCE_ATTRIBUTES value from job name and user tags.
     * Always includes job_name automatically.
     */
    internal fun buildResourceAttributes(
        jobName: String,
        tags: Map<String, String>,
    ): String {
        val allAttributes = mutableMapOf("job_name" to jobName)
        allAttributes.putAll(tags)
        return allAttributes.entries.joinToString(",") { "${it.key}=${it.value}" }
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
        tags: Map<String, String> = emptyMap(),
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

        val resourceAttributes = buildResourceAttributes(jobName, tags)

        val otelSidecar =
            ContainerBuilder()
                .withName("otel-sidecar")
                .withImage("otel/opentelemetry-collector-contrib:latest")
                .withArgs("--config=/etc/otel/$SIDECAR_CONFIG_FILE_NAME")
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
                    EnvVarBuilder()
                        .withName("OTEL_RESOURCE_ATTRIBUTES")
                        .withValue(resourceAttributes)
                        .build(),
                ).withResources(
                    ResourceRequirementsBuilder()
                        .addToRequests("memory", Quantity("32Mi"))
                        .addToRequests("cpu", Quantity("25m"))
                        .build(),
                ).withRestartPolicy("Always")
                .withVolumeMounts(
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
            .withInitContainers(otelSidecar)
            .withContainers(stressContainer)
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
