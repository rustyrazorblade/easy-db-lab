package com.rustyrazorblade.easydblab.configuration.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.agents.JavaAgent
import com.rustyrazorblade.easydblab.configuration.agents.JavaAgentDelivery
import com.rustyrazorblade.easydblab.configuration.otel.OtelEndpoints
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import com.rustyrazorblade.easydblab.services.StressJobConfig
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder

/**
 * The cluster facts a stress Job's environment and agents are built from: which cluster and tenant
 * it is, where profiles go, and which nodes the app role runs on. The agents reach the collector
 * and Pyroscope by their Services, never by a node address. Read once from the cluster state by
 * [from], so [StressJobManifestBuilder] never touches the state itself.
 */
data class StressCluster(
    val clusterName: String,
    val tenant: String,
    val telemetryRedirect: TelemetryRedirect?,
    val appSelector: NodeSelector,
) {
    companion object {
        /** Reads the facts from [state]. */
        fun from(state: ClusterState): StressCluster =
            StressCluster(
                clusterName = state.clusterLabelName(),
                tenant = state.tenant(),
                telemetryRedirect = state.initConfig?.telemetryRedirect,
                appSelector = RoleSelectors.from(state).app,
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
    private const val STRESS_CONTAINER = "stress"

    // Both agents reach the stress JVM from init containers (JavaAgentDelivery), never a host
    // path, so a stress pod runs on any node of any cluster.
    private val STRESS_AGENTS = listOf(JavaAgent.PYROSCOPE, JavaAgent.OTEL)

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

        val podSpec = buildRunPodSpec(stressContainer, otelSidecar, cluster.appSelector)
        return wrapInJob(config.jobName, labels, TTL_STRESS_JOB_SECONDS, podSpec)
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
                JavaAgentDelivery.flag(JavaAgent.PYROSCOPE),
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
                JavaAgentDelivery.flag(JavaAgent.OTEL),
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
            .withName(STRESS_CONTAINER)
            .withImage(config.image)
            .withArgs(config.args)
            .withEnv(
                EnvVarBuilder()
                    .withName("CASSANDRA_CONTACT_POINTS")
                    .withValue(config.target.contactPoint)
                    .build(),
                EnvVarBuilder()
                    .withName("CASSANDRA_PORT")
                    .withValue(config.target.port.toString())
                    .build(),
                EnvVarBuilder()
                    .withName("CASSANDRA_EASY_STRESS_DEFAULT_DC")
                    .withValue(config.target.datacenter)
                    .build(),
                EnvVarBuilder()
                    .withName("CASSANDRA_EASY_STRESS_PROM_PORT")
                    .withValue(config.promPort.toString())
                    .build(),
                EnvVarBuilder()
                    .withName("JDK_JAVA_OPTIONS")
                    .withValue(stressJavaOptions(config, cluster))
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

    /**
     * The stress run's pod: on the pod network, on nodes matching [appSelector], with the OTel
     * sidecar first and the Java agents copied in by init containers after it.
     */
    private fun buildRunPodSpec(
        stressContainer: Container,
        otelSidecar: Container,
        appSelector: NodeSelector,
    ): PodSpec {
        val spec =
            PodSpecBuilder()
                .withRestartPolicy("Never")
                .withNodeSelector<String, String>(appSelector.labels)
                .withInitContainers(otelSidecar)
                .withContainers(stressContainer)
                .withVolumes(
                    VolumeBuilder()
                        .withName("otel-sidecar-config")
                        .withNewConfigMap()
                        .withName(StressSidecarConfig.CONFIG_MAP_NAME)
                        .endConfigMap()
                        .build(),
                ).build()
        return JavaAgentDelivery.applyTo(spec, STRESS_CONTAINER, STRESS_AGENTS)
    }

    /** A Job named [jobName] in the stress namespace that runs [podSpec] once and is removed [ttlSeconds] after it finishes. */
    private fun wrapInJob(
        jobName: String,
        labels: Map<String, String>,
        ttlSeconds: Int,
        podSpec: PodSpec,
    ): Job =
        JobBuilder()
            .withNewMetadata()
            .withName(jobName)
            .withNamespace(Constants.Stress.NAMESPACE)
            .withLabels<String, String>(labels)
            .endMetadata()
            .withNewSpec()
            .withBackoffLimit(0)
            .withTtlSecondsAfterFinished(ttlSeconds)
            .withNewTemplate()
            .withNewMetadata()
            .withLabels<String, String>(labels)
            .endMetadata()
            .withSpec(podSpec)
            .endTemplate()
            .endSpec()
            .build()

    /**
     * Builds a simple Kubernetes Job for short-lived commands, on the pod network, on nodes
     * matching [appSelector].
     */
    fun buildCommandJob(
        jobName: String,
        image: String,
        args: List<String>,
        appSelector: NodeSelector,
    ): Job {
        val labels =
            mapOf(
                Constants.Stress.LABEL_KEY to Constants.Stress.LABEL_VALUE,
                Constants.Stress.ROLE_LABEL_KEY to Constants.Stress.ROLE_COMMAND,
                "job-name" to jobName,
            )

        val stressContainer =
            ContainerBuilder()
                .withName(STRESS_CONTAINER)
                .withImage(image)
                .withArgs(args)
                .build()

        val podSpec =
            PodSpecBuilder()
                .withRestartPolicy("Never")
                .withNodeSelector<String, String>(appSelector.labels)
                .withContainers(stressContainer)
                .build()
        return wrapInJob(jobName, labels, TTL_COMMAND_JOB_SECONDS, podSpec)
    }
}
