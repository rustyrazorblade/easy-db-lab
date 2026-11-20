package com.rustyrazorblade.easydblab.configuration.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.otel.OtelEndpoints
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import com.rustyrazorblade.easydblab.services.StressJobConfig
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder

/**
 * The cluster facts a stress Job's environment and agents are built from: which region and tenant
 * the cluster is, and where profiles go. The agents reach the collector and Pyroscope by their
 * Services, never by a node address. Read once from the cluster
 * state by [from], so [StressJobManifestBuilder] never touches the state itself.
 */
data class StressCluster(
    val region: String,
    val clusterName: String,
    val tenant: String,
    val telemetryRedirect: TelemetryRedirect?,
) {
    companion object {
        /** Reads the facts from [state]; fails when it holds no region. */
        fun from(state: ClusterState): StressCluster =
            StressCluster(
                region =
                    state.infrastructure?.region
                        ?: error("Infrastructure state missing region. Re-provision the cluster to fix this."),
                clusterName = state.clusterLabelName(),
                tenant = state.tenant(),
                telemetryRedirect = state.initConfig?.telemetryRedirect,
            )
    }
}

/**
 * Builds the Kubernetes Jobs for cassandra-easy-stress: the long-running stress Job with its OTel
 * sidecar, and the short-lived command Job. Pure: it turns a [StressJobConfig] and the
 * [StressCluster] facts into fabric8 objects, loading no state and calling no cluster, so a test of
 * the Job's shape needs neither a state file nor a cluster. The lifecycle (create, wait, delete)
 * belongs to `DefaultStressJobService`; the sidecar's ConfigMap belongs to [StressSidecarConfig].
 */
object StressJobManifestBuilder {
    private const val TTL_STRESS_JOB_SECONDS = 86400
    private const val TTL_COMMAND_JOB_SECONDS = 300
    private const val PYROSCOPE_VOLUME_NAME = "pyroscope-agent"
    private const val PYROSCOPE_HOST_PATH = "/usr/local/pyroscope"
    private const val PYROSCOPE_MOUNT_PATH = "/usr/local/pyroscope"

    // The OTel Java agent, reaching the pod the same way the Pyroscope agent does: a hostPath
    // mount of where the base AMI installs it. The job's nodeSelector pins it to a `type=app`
    // node, and the base image puts the jar there, so the path resolves on every node this job
    // can land on.
    private const val OTEL_AGENT_VOLUME_NAME = "otel-agent"
    private const val OTEL_AGENT_HOST_PATH = "/usr/local/otel"
    private const val OTEL_AGENT_MOUNT_PATH = "/usr/local/otel"

    /** Builds the stress Job for [config] on [cluster], with the OTel sidecar. */
    fun buildStressJob(
        config: StressJobConfig,
        cluster: StressCluster,
    ): Job {
        val labels =
            mapOf(
                Constants.Stress.LABEL_KEY to Constants.Stress.LABEL_VALUE,
                Constants.Stress.ROLE_LABEL_KEY to Constants.Stress.ROLE_RUN,
                "job-name" to config.jobName,
            )

        val stressContainer = buildStressContainer(config, cluster)
        val otelSidecar = buildOtelSidecarContainer(config.jobName, config.tags, config.promPort, cluster.clusterName)

        return assembleJob(config.jobName, labels, stressContainer, otelSidecar)
    }

    /**
     * Builds the OTEL_RESOURCE_ATTRIBUTES value from job name and user tags.
     * Always includes job_name automatically.
     */
    fun buildResourceAttributes(
        jobName: String,
        tags: Map<String, String>,
    ): String {
        val allAttributes = mutableMapOf("job_name" to jobName)
        allAttributes.putAll(tags)
        return allAttributes.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    /** The stress JVM's `JDK_JAVA_OPTIONS`: the Pyroscope and OTel agents and their settings. */
    private fun stressJavaOptions(
        config: StressJobConfig,
        cluster: StressCluster,
    ): String {
        val pyroscopeServerAddress = pyroscopeIngestBaseUrl(cluster.telemetryRedirect)
        val pyroscopeLabels = "cluster=${cluster.clusterName},job_name=${config.jobName}"

        // Client spans come from here or from nowhere. Cassandra has no OTel server-side
        // instrumentation, so the only thing on this cluster that can produce a trace is the driver
        // in this JVM; without the agent, Tempo receives nothing and every spanmetrics panel is
        // empty. Two agents in one JVM, exactly as Cassandra runs OTel beside AxonOps.
        //
        // Port 4318, not 4317: the agent's default OTLP protocol is http/protobuf, which the
        // collector serves on 4318. Sent at 4317 - its gRPC port - every export fails with
        // "HttpExporter - Failed to export" and nothing says why.
        //
        // Resource attributes come from the same helper the sidecar uses, so a span and the stress
        // metrics from the same run carry the same job_name and tags and can be lined up. The
        // cluster label is NOT added here: the collector's traces pipeline stamps it with
        // resource/cluster, so every span producer gets it, not just this one.
        //
        // Logs are the one signal turned off. The pod's stdout already reaches Loki through
        // the collector's filelog receiver, so exporting them again from inside the JVM would file
        // every line twice. Metrics stay ON: the sidecar scrapes only cassandra-easy-stress's own
        // Prometheus counters, so the agent's JVM metrics are the only view of whether the load
        // generator itself is the bottleneck.
        //
        // JDK_JAVA_OPTIONS, not JAVA_TOOL_OPTIONS: the cassandra-easy-stress image selects ZGC through
        // JAVA_TOOL_OPTIONS, and a pod env var replaces the image's value, so setting it here ran
        // stress on G1. The image's entrypoint is the java launcher, which reads JDK_JAVA_OPTIONS as
        // well, so the agents and the image's GC choice both apply.
        //
        // Traces are sampled at Constants.Stress.TRACE_SAMPLE_RATIO: tracing every request sent
        // Tempo on the control node more spans than it could take in.
        val otelResourceAttributes = buildResourceAttributes(config.jobName, config.tags)

        val pyroscope =
            listOf(
                "-javaagent:$PYROSCOPE_MOUNT_PATH/pyroscope.jar",
                "-Dpyroscope.application.name=cassandra-easy-stress",
                "-Dpyroscope.server.address=$pyroscopeServerAddress",
            ) + Constants.PyroscopeJavaAgent.PROFILER_FLAGS +
                listOf(
                    "-Dpyroscope.labels=$pyroscopeLabels",
                    // Pyroscope runs native multi-tenancy; the agent sends this as X-Scope-OrgID.
                    "-Dpyroscope.tenant.id=${cluster.tenant}",
                ) + Constants.PyroscopeJavaAgent.OUTAGE_FLAGS
        val otel =
            listOf(
                "-javaagent:$OTEL_AGENT_MOUNT_PATH/opentelemetry-javaagent.jar",
                "-Dotel.service.name=cassandra-easy-stress",
                "-Dotel.resource.attributes=$otelResourceAttributes",
                "-Dotel.exporter.otlp.endpoint=${OtelEndpoints.otlpHttpEndpoint()}",
                "-Dotel.metric.export.interval=5s",
                "-Dotel.logs.exporter=none",
                "-Dotel.traces.sampler=parentbased_traceidratio",
                "-Dotel.traces.sampler.arg=${Constants.Stress.TRACE_SAMPLE_RATIO}",
            )
        return (pyroscope + otel).joinToString(" ")
    }

    private fun buildStressContainer(
        config: StressJobConfig,
        cluster: StressCluster,
    ): Container =
        ContainerBuilder()
            .withName("stress")
            .withImage(config.image)
            .withArgs(config.args)
            .withEnv(
                EnvVarBuilder()
                    .withName("CASSANDRA_CONTACT_POINTS")
                    .withValue(config.contactPoints)
                    .build(),
                EnvVarBuilder()
                    .withName("CASSANDRA_PORT")
                    .withValue(Constants.Stress.DEFAULT_CASSANDRA_PORT.toString())
                    .build(),
                EnvVarBuilder()
                    .withName("CASSANDRA_EASY_STRESS_DEFAULT_DC")
                    .withValue(cluster.region)
                    .build(),
                EnvVarBuilder()
                    .withName("CASSANDRA_EASY_STRESS_PROM_PORT")
                    .withValue(config.promPort.toString())
                    .build(),
                EnvVarBuilder()
                    .withName("JDK_JAVA_OPTIONS")
                    .withValue(stressJavaOptions(config, cluster))
                    .build(),
            ).withVolumeMounts(
                VolumeMountBuilder()
                    .withName(PYROSCOPE_VOLUME_NAME)
                    .withMountPath(PYROSCOPE_MOUNT_PATH)
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName(OTEL_AGENT_VOLUME_NAME)
                    .withMountPath(OTEL_AGENT_MOUNT_PATH)
                    .withReadOnly(true)
                    .build(),
            ).build()

    private fun buildOtelSidecarContainer(
        jobName: String,
        tags: Map<String, String>,
        promPort: Int,
        clusterName: String,
    ): Container {
        val resourceAttributes = buildResourceAttributes(jobName, tags)

        return ContainerBuilder()
            .withName("otel-sidecar")
            .withImage("otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}")
            .withArgs("--config=/etc/otel/${StressSidecarConfig.FILE_NAME}")
            .withEnv(
                EnvVarBuilder()
                    .withName("K8S_NODE_NAME")
                    .withNewValueFrom()
                    .withNewFieldRef()
                    .withFieldPath("spec.nodeName")
                    .endFieldRef()
                    .endValueFrom()
                    .build(),
                // Inject the cluster label directly from cluster state rather than via a
                // `cluster-config` ConfigMap. That ConfigMap is created by the separate
                // `grafana update-config` command and is not guaranteed to exist when a stress
                // job starts; a non-optional configMapKeyRef to a missing ConfigMap causes
                // CreateContainerConfigError and fails the Job (issue #733). The value matches
                // `clusterLabelName()` so the sidecar's `cluster` metric label lines up with the
                // rest of the observability stack.
                EnvVarBuilder()
                    .withName("CLUSTER_NAME")
                    .withValue(clusterName)
                    .build(),
                // No GOMEMLIMIT: the sidecar queues up to 20000 batches through a node collector
                // outage (issue 991), and a soft limit below that queue would keep the Go GC
                // running and take CPU from the workload under test.
                EnvVarBuilder()
                    .withName("OTEL_RESOURCE_ATTRIBUTES")
                    .withValue(resourceAttributes)
                    .build(),
                EnvVarBuilder()
                    .withName("STRESS_PROM_PORT")
                    .withValue(promPort.toString())
                    .build(),
            ).withRestartPolicy("Always")
            .withVolumeMounts(
                VolumeMountBuilder()
                    .withName("otel-sidecar-config")
                    .withMountPath("/etc/otel")
                    .withReadOnly(true)
                    .build(),
            ).build()
    }

    private fun assembleJob(
        jobName: String,
        labels: Map<String, String>,
        stressContainer: Container,
        otelSidecar: Container,
    ): Job =
        JobBuilder()
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
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .withRestartPolicy("Never")
            .withNodeSelector<String, String>(mapOf("type" to ServerType.Stress.serverType))
            .withInitContainers(otelSidecar)
            .withContainers(stressContainer)
            .withVolumes(
                VolumeBuilder()
                    .withName("otel-sidecar-config")
                    .withNewConfigMap()
                    .withName(StressSidecarConfig.CONFIG_MAP_NAME)
                    .endConfigMap()
                    .build(),
                VolumeBuilder()
                    .withName(PYROSCOPE_VOLUME_NAME)
                    .withNewHostPath()
                    .withPath(PYROSCOPE_HOST_PATH)
                    .withType("Directory")
                    .endHostPath()
                    .build(),
                VolumeBuilder()
                    .withName(OTEL_AGENT_VOLUME_NAME)
                    .withNewHostPath()
                    .withPath(OTEL_AGENT_HOST_PATH)
                    .withType("Directory")
                    .endHostPath()
                    .build(),
            ).endSpec()
            .endTemplate()
            .endSpec()
            .build()

    /**
     * Builds a simple Kubernetes Job for short-lived commands.
     */
    fun buildCommandJob(
        jobName: String,
        image: String,
        args: List<String>,
    ): Job {
        val labels =
            mapOf(
                Constants.Stress.LABEL_KEY to Constants.Stress.LABEL_VALUE,
                Constants.Stress.ROLE_LABEL_KEY to Constants.Stress.ROLE_COMMAND,
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
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .withRestartPolicy("Never")
            .withNodeSelector<String, String>(mapOf("type" to ServerType.Stress.serverType))
            .withContainers(stressContainer)
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }
}
