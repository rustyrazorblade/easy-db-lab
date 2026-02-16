package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.output.OutputHandler
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
     * @param profileConfig Optional profile file content (key=filename, value=content)
     * @return Result containing the created job name or failure
     */
    fun startJob(
        controlHost: ClusterHost,
        jobName: String,
        image: String,
        args: List<String>,
        contactPoints: String,
        profileConfig: Pair<String, String>? = null,
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
    }

    override fun startJob(
        controlHost: ClusterHost,
        jobName: String,
        image: String,
        args: List<String>,
        contactPoints: String,
        profileConfig: Pair<String, String>?,
    ): Result<String> =
        runCatching {
            log.info { "Starting stress job: $jobName" }

            // Create ConfigMap for profile if provided
            var profileConfigMapName: String? = null
            var profileFileName: String? = null
            if (profileConfig != null) {
                profileFileName = profileConfig.first
                profileConfigMapName = "$jobName-profile"

                outputHandler.handleMessage("Creating ConfigMap for profile: $profileFileName")

                k8sService
                    .createConfigMap(
                        controlHost,
                        Constants.Stress.NAMESPACE,
                        profileConfigMapName,
                        mapOf(profileFileName to profileConfig.second),
                        mapOf(
                            Constants.Stress.LABEL_KEY to Constants.Stress.LABEL_VALUE,
                            "job-name" to jobName,
                        ),
                    ).getOrThrow()
            }

            // Build job YAML
            val jobYaml =
                buildJobYaml(
                    jobName = jobName,
                    image = image,
                    contactPoints = contactPoints,
                    args = args,
                    profileConfigMapName = profileConfigMapName,
                )
            log.debug { "Job YAML:\n$jobYaml" }

            // Create the job
            outputHandler.handleMessage("Starting stress job: $jobName")
            k8sService
                .createJob(controlHost, Constants.Stress.NAMESPACE, jobYaml)
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

            // Try to delete associated ConfigMap (may not exist)
            val configMapName = "$jobName-profile"
            k8sService
                .deleteConfigMap(controlHost, Constants.Stress.NAMESPACE, configMapName)
                .onFailure { log.debug { "No ConfigMap to delete: $configMapName" } }

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

            // Build a simple job without profile mounting
            val jobYaml = buildCommandJobYaml(jobName, image, args)

            // Create and run the job
            k8sService
                .createJob(controlHost, Constants.Stress.NAMESPACE, jobYaml)
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
     * Builds the Kubernetes Job YAML for a stress job.
     */
    internal fun buildJobYaml(
        jobName: String,
        image: String,
        contactPoints: String,
        args: List<String>,
        profileConfigMapName: String?,
    ): String {
        val argsYaml = args.joinToString("\n") { "            - \"$it\"" }

        val volumeMountsYaml =
            if (profileConfigMapName != null) {
                """
          volumeMounts:
            - name: profiles
              mountPath: ${Constants.Stress.PROFILE_MOUNT_PATH}
              readOnly: true"""
            } else {
                ""
            }

        val profileVolumeYaml =
            if (profileConfigMapName != null) {
                """
        - name: profiles
          configMap:
            name: $profileConfigMapName"""
            } else {
                ""
            }

        return """
apiVersion: batch/v1
kind: Job
metadata:
  name: $jobName
  namespace: ${Constants.Stress.NAMESPACE}
  labels:
    ${Constants.Stress.LABEL_KEY}: ${Constants.Stress.LABEL_VALUE}
    job-name: $jobName
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 86400
  template:
    metadata:
      labels:
        ${Constants.Stress.LABEL_KEY}: ${Constants.Stress.LABEL_VALUE}
        job-name: $jobName
    spec:
      restartPolicy: Never
      nodeSelector:
        type: ${ServerType.Stress.serverType}
      containers:
        - name: stress
          image: $image
          env:
            - name: CASSANDRA_CONTACT_POINTS
              value: "$contactPoints"
            - name: CASSANDRA_PORT
              value: "${Constants.Stress.DEFAULT_CASSANDRA_PORT}"
          command: ["cassandra-easy-stress"]
          args:
$argsYaml
          resources:
            requests:
              memory: "1Gi"
              cpu: "500m"
            limits:
              memory: "4Gi"$volumeMountsYaml
        - name: otel-sidecar
          image: otel/opentelemetry-collector-contrib:latest
          args:
            - --config=/etc/otel/otel-stress-sidecar-config.yaml
          env:
            - name: K8S_NODE_NAME
              valueFrom:
                fieldRef:
                  fieldPath: spec.nodeName
            - name: HOST_IP
              valueFrom:
                fieldRef:
                  fieldPath: status.hostIP
            - name: CLUSTER_NAME
              valueFrom:
                configMapKeyRef:
                  name: cluster-config
                  key: cluster_name
            - name: GOMEMLIMIT
              value: "64MiB"
          resources:
            requests:
              memory: "32Mi"
              cpu: "25m"
            limits:
              memory: "64Mi"
          volumeMounts:
            - name: otel-sidecar-config
              mountPath: /etc/otel
              readOnly: true
      volumes:
        - name: otel-sidecar-config
          configMap:
            name: otel-stress-sidecar-config$profileVolumeYaml
""".trimStart()
    }

    /**
     * Builds a simple job YAML for short-lived commands.
     */
    internal fun buildCommandJobYaml(
        jobName: String,
        image: String,
        args: List<String>,
    ): String {
        val argsYaml = args.joinToString("\n") { "            - \"$it\"" }

        return """
apiVersion: batch/v1
kind: Job
metadata:
  name: $jobName
  namespace: ${Constants.Stress.NAMESPACE}
  labels:
    ${Constants.Stress.LABEL_KEY}: ${Constants.Stress.LABEL_VALUE}
    job-name: $jobName
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 300
  template:
    metadata:
      labels:
        ${Constants.Stress.LABEL_KEY}: ${Constants.Stress.LABEL_VALUE}
        job-name: $jobName
    spec:
      restartPolicy: Never
      nodeSelector:
        type: ${ServerType.Stress.serverType}
      containers:
        - name: stress
          image: $image
          command: ["cassandra-easy-stress"]
          args:
$argsYaml
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
""".trimStart()
    }
}
