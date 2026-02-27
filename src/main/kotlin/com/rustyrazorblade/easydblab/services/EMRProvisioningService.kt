package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.emr.OtelBootstrapResource
import com.rustyrazorblade.easydblab.configuration.s3Path
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.BootstrapAction
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterConfig
import com.rustyrazorblade.easydblab.providers.aws.EMRConfiguration
import com.rustyrazorblade.easydblab.services.aws.EMRService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.inputStream

/**
 * Service for provisioning EMR clusters for Spark workloads.
 *
 * Encapsulates the logic for creating an EMR cluster from cluster configuration,
 * used by both the `up` command (via ClusterProvisioningService) and the standalone
 * `spark init` command.
 */
interface EMRProvisioningService {
    /**
     * Provisions an EMR cluster using cluster configuration parameters.
     *
     * @param clusterName Base name for the EMR cluster (will have "-spark" appended)
     * @param masterInstanceType Instance type for the master node
     * @param workerInstanceType Instance type for core/worker nodes
     * @param workerCount Number of core/worker nodes
     * @param subnetId Subnet ID where the cluster will be launched
     * @param securityGroupId Security group for cluster access
     * @param keyName SSH key pair name for cluster instances
     * @param clusterState Current cluster state (for S3 log path)
     * @param tags Tags to apply to the cluster
     * @return Created EMR cluster state
     */
    fun provisionEmrCluster(
        clusterName: String,
        masterInstanceType: String,
        workerInstanceType: String,
        workerCount: Int,
        subnetId: String,
        securityGroupId: String,
        keyName: String,
        clusterState: ClusterState,
        tags: Map<String, String>,
    ): EMRClusterState
}

/**
 * Default implementation of EMRProvisioningService.
 *
 * Creates an EMR cluster via [EMRService] and waits for it to reach a ready state.
 */
class DefaultEMRProvisioningService(
    private val emrService: EMRService,
    private val objectStore: ObjectStore,
    private val templateService: TemplateService,
    private val eventBus: EventBus,
) : EMRProvisioningService {
    private val log = KotlinLogging.logger {}

    override fun provisionEmrCluster(
        clusterName: String,
        masterInstanceType: String,
        workerInstanceType: String,
        workerCount: Int,
        subnetId: String,
        securityGroupId: String,
        keyName: String,
        clusterState: ClusterState,
        tags: Map<String, String>,
    ): EMRClusterState {
        eventBus.emit(Event.Emr.SparkClusterCreating)

        val s3Path = clusterState.s3Path()
        val bootstrapAction = uploadOtelBootstrapScript(s3Path)

        val sparkDefaults = buildSparkDefaultsConfiguration(clusterState)
        val sparkEnv = buildSparkEnvConfiguration()

        val emrConfig =
            EMRClusterConfig(
                clusterName = "$clusterName-spark",
                logUri = s3Path.emrLogs().toString(),
                subnetId = subnetId,
                ec2KeyName = keyName,
                masterInstanceType = masterInstanceType,
                coreInstanceType = workerInstanceType,
                coreInstanceCount = workerCount,
                additionalSecurityGroups = listOf(securityGroupId),
                tags = tags,
                bootstrapActions = listOf(bootstrapAction),
                configurations = listOf(sparkDefaults, sparkEnv),
            )

        val result = emrService.createCluster(emrConfig)

        val readyResult =
            try {
                emrService.waitForClusterReady(result.clusterId)
            } catch (e: IllegalStateException) {
                emitBootstrapFailureDiagnostics(result.clusterId, s3Path)
                throw e
            }

        return EMRClusterState(
            clusterId = readyResult.clusterId,
            clusterName = readyResult.clusterName,
            masterPublicDns = readyResult.masterPublicDns,
            state = readyResult.state,
        )
    }

    /**
     * Fetches and emits bootstrap action stderr logs when an EMR cluster fails to start.
     * Falls back to emitting manual debug instructions if logs cannot be retrieved.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun emitBootstrapFailureDiagnostics(
        clusterId: String,
        s3Path: ClusterS3Path,
    ) {
        eventBus.emit(Event.Emr.BootstrapFailureDiagnosing)

        try {
            val masterInstances = emrService.listInstances(clusterId)
            if (masterInstances.isEmpty()) {
                eventBus.emit(Event.Emr.BootstrapFailureLogUnavailable("No master instance found"))
                emitDebugInstructions(clusterId, s3Path)
                return
            }

            val instanceId = masterInstances.first()
            val stderrPath =
                s3Path
                    .emrLogs()
                    .resolve(clusterId)
                    .resolve("node/$instanceId/bootstrap-actions/1/stderr.gz")

            val tempFile = createTempFile("bootstrap-stderr", ".gz")
            objectStore.downloadFile(stderrPath, tempFile, showProgress = false)

            val content =
                GZIPInputStream(tempFile.inputStream()).use { gzStream ->
                    BufferedReader(InputStreamReader(gzStream)).readText()
                }

            eventBus.emit(Event.Emr.BootstrapFailureLog(content))
        } catch (e: Exception) {
            log.debug(e) { "Failed to retrieve bootstrap logs for cluster $clusterId" }
            eventBus.emit(
                Event.Emr.BootstrapFailureLogUnavailable(
                    e.message ?: "Unknown error",
                ),
            )
            emitDebugInstructions(clusterId, s3Path)
        }
    }

    private fun emitDebugInstructions(
        clusterId: String,
        s3Path: ClusterS3Path,
    ) {
        eventBus.emit(
            Event.Emr.BootstrapFailureDebugInstructions(
                clusterId = clusterId,
                emrLogsPath = s3Path.emrLogs().toString(),
            ),
        )
    }

    /**
     * Builds a spark-defaults EMR classification that activates the OTel and Pyroscope
     * Java agents for all Spark JVMs (driver, executor, YARN app master) cluster-wide.
     * The agents are installed by the bootstrap action; this classification activates them.
     */
    private fun buildSparkDefaultsConfiguration(clusterState: ClusterState): EMRConfiguration {
        val controlIp =
            clusterState.hosts[ServerType.Control]
                ?.firstOrNull()
                ?.privateIp
                ?: error("No control node found in cluster state for spark-defaults configuration")

        val otelAgentFlag = "-javaagent:${Constants.OtelJavaAgent.INSTALL_PATH}"
        val pyroscopeFlags =
            listOf(
                "-javaagent:${Constants.PyroscopeJavaAgent.INSTALL_PATH}",
                "-Dpyroscope.application.name=spark",
                "-Dpyroscope.server.address=http://$controlIp:${Constants.K8s.PYROSCOPE_PORT}",
                "-Dpyroscope.format=jfr",
                "-Dpyroscope.profiler.event=cpu",
                "-Dpyroscope.profiler.alloc=512k",
                "-Dpyroscope.profiler.lock=10ms",
            )
        val extraJavaOptions = "$otelAgentFlag ${pyroscopeFlags.joinToString(" ")}"

        val otelEnvVars =
            mapOf(
                "OTEL_LOGS_EXPORTER" to "otlp",
                "OTEL_METRICS_EXPORTER" to "otlp",
                "OTEL_TRACES_EXPORTER" to "otlp",
                "OTEL_SERVICE_NAME" to "spark",
            )

        val properties =
            mutableMapOf(
                "spark.driver.extraJavaOptions" to extraJavaOptions,
                "spark.executor.extraJavaOptions" to extraJavaOptions,
            )

        for ((key, value) in otelEnvVars) {
            properties["spark.driverEnv.$key"] = value
            properties["spark.executorEnv.$key"] = value
            properties["spark.yarn.appMasterEnv.$key"] = value
        }

        return EMRConfiguration(
            classification = "spark-defaults",
            properties = properties,
        )
    }

    /**
     * Builds a spark-env EMR classification that exports PYROSCOPE_LABELS with the node hostname.
     * spark-env.sh is sourced by YARN before launching Spark processes, so $(hostname) resolves
     * per-node at runtime. This allows the Pyroscope Java agent to tag profiles with hostname.
     */
    private fun buildSparkEnvConfiguration(): EMRConfiguration =
        EMRConfiguration(
            classification = "spark-env",
            configurations = listOf(
                EMRConfiguration(
                    classification = "export",
                    properties = mapOf(
                        "PYROSCOPE_LABELS" to "hostname=\$(hostname -s)",
                    ),
                ),
            ),
        )

    /**
     * Uploads the OTel bootstrap script to S3 and returns a BootstrapAction for it.
     * The bootstrap script installs:
     * - OTel Java agent (for Spark JVM instrumentation)
     * - OTel Collector (systemd service for host metrics and OTLP forwarding)
     * - Pyroscope Java agent (for continuous profiling)
     *
     * The control node IP is resolved at upload time by TemplateService
     * (via __CONTROL_NODE_IP__ in the collector config).
     */
    private fun uploadOtelBootstrapScript(s3Path: ClusterS3Path): BootstrapAction {
        val collectorConfig =
            templateService
                .fromResource(OtelBootstrapResource::class.java, "otel-collector-config.yaml")
                .substitute()

        val scriptContent =
            templateService
                .fromResource(OtelBootstrapResource::class.java, "bootstrap-otel.sh")
                .substitute(
                    mapOf(
                        "OTEL_AGENT_DOWNLOAD_URL" to Constants.OtelJavaAgent.DOWNLOAD_URL,
                        "OTEL_COLLECTOR_DOWNLOAD_URL" to Constants.OtelCollector.DOWNLOAD_URL,
                        "PYROSCOPE_AGENT_DOWNLOAD_URL" to Constants.PyroscopeJavaAgent.DOWNLOAD_URL,
                        "OTEL_COLLECTOR_CONFIG" to collectorConfig,
                    ),
                )

        val scriptS3Path = s3Path.spark().resolve("bootstrap-otel.sh")

        log.info { "Uploading OTel bootstrap script to $scriptS3Path" }
        objectStore.uploadContent(scriptContent, scriptS3Path)

        return BootstrapAction(
            name = "Install OTel and Pyroscope Agents",
            scriptS3Path = scriptS3Path.toString(),
        )
    }
}
