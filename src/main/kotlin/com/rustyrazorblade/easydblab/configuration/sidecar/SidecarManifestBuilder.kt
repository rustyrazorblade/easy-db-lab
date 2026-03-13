package com.rustyrazorblade.easydblab.configuration.sidecar

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder

/**
 * Builds the cassandra-sidecar K8s DaemonSet resource.
 *
 * Runs ghcr.io/apache/cassandra-sidecar:latest on all db nodes via k3s.
 * Replaces the packer-based git clone + Gradle build approach, saving 5-10 minutes
 * per AMI build and always using the latest pre-built image.
 *
 * The DaemonSet:
 * - Targets nodes with label `type=db` (nodeSelector)
 * - Uses hostNetwork so the sidecar can reach Cassandra on localhost
 * - Mounts config, data, and Java agent directories from the host
 * - Injects JAVA_OPTS with OTel and Pyroscope agents using K8s env var interpolation
 */
class SidecarManifestBuilder {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "cassandra-sidecar"
        private const val IMAGE = "ghcr.io/apache/cassandra-sidecar:latest"

        private const val SIDECAR_CONFIG_PATH = "file:///etc/cassandra-sidecar/cassandra-sidecar.yaml"
        private const val SIDECAR_LOG_DIR = "/mnt/db1/cassandra/logs/sidecar"
        private const val OTEL_AGENT_PATH = "/usr/local/otel/opentelemetry-javaagent.jar"
        private const val PYROSCOPE_AGENT_PATH = "/usr/local/pyroscope/pyroscope.jar"

        private val JAVA_OPTS =
            "-Dsidecar.config=$SIDECAR_CONFIG_PATH " +
                "-Dsidecar.logdir=$SIDECAR_LOG_DIR " +
                "-javaagent:$OTEL_AGENT_PATH " +
                "-javaagent:$PYROSCOPE_AGENT_PATH " +
                "-Dpyroscope.application.name=$APP_LABEL " +
                "-Dpyroscope.server.address=http://\$(CONTROL_NODE_IP):4040 " +
                "-Dpyroscope.labels=hostname:\$(HOSTNAME),cluster:\$(CLUSTER_NAME) " +
                "-Dpyroscope.profiler.event=cpu " +
                "-Dpyroscope.profiler.alloc=512k " +
                "-Dpyroscope.profiler.lock=10ms"
    }

    /**
     * Builds all sidecar K8s resources in apply order.
     *
     * @return List of: DaemonSet
     */
    fun buildAllResources(): List<HasMetadata> = listOf(buildDaemonSet())

    /**
     * Builds the cassandra-sidecar DaemonSet.
     *
     * Runs on db nodes only (nodeSelector type=db) with hostNetwork so the sidecar
     * can reach Cassandra on localhost:9042. Env vars use K8s $(VAR_NAME) interpolation
     * to embed per-node hostname and cluster config from the cluster-config ConfigMap.
     */
    @Suppress("LongMethod")
    fun buildDaemonSet() =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addToNodeSelector("type", "db")
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            // Per-node hostname from K8s downward API
            .addNewEnv()
            .withName("HOSTNAME")
            .withNewValueFrom()
            .withNewFieldRef()
            .withFieldPath("spec.nodeName")
            .endFieldRef()
            .endValueFrom()
            .endEnv()
            // Control node IP from cluster-config ConfigMap
            .addNewEnv()
            .withName("CONTROL_NODE_IP")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName("cluster-config")
            .withKey("control_node_ip")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            // Cluster name from cluster-config ConfigMap
            .addNewEnv()
            .withName("CLUSTER_NAME")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName("cluster-config")
            .withKey("cluster_name")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            // JAVA_OPTS with sidecar config path, OTel agent, and Pyroscope agent
            // Uses K8s $(VAR_NAME) interpolation to embed per-node values
            .addNewEnv()
            .withName("JAVA_OPTS")
            .withValue(JAVA_OPTS)
            .endEnv()
            .addNewEnv()
            .withName("OTEL_SERVICE_NAME")
            .withValue(APP_LABEL)
            .endEnv()
            .addNewEnv()
            .withName("OTEL_EXPORTER_OTLP_ENDPOINT")
            .withValue("http://localhost:4317")
            .endEnv()
            .addToVolumeMounts(
                // Sidecar config directory (read-only)
                VolumeMountBuilder()
                    .withName("sidecar-config")
                    .withMountPath("/etc/cassandra-sidecar")
                    .withReadOnly(true)
                    .build(),
                // Data, logs, and staging directories
                VolumeMountBuilder()
                    .withName("cassandra-data")
                    .withMountPath("/mnt/db1/cassandra")
                    .build(),
                // OTel Java agent (read-only)
                VolumeMountBuilder()
                    .withName("otel-agent")
                    .withMountPath("/usr/local/otel")
                    .withReadOnly(true)
                    .build(),
                // Pyroscope Java agent (read-only)
                VolumeMountBuilder()
                    .withName("pyroscope-agent")
                    .withMountPath("/usr/local/pyroscope")
                    .withReadOnly(true)
                    .build(),
            ).endContainer()
            // Sidecar config hostPath volume
            .addNewVolume()
            .withName("sidecar-config")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/etc/cassandra-sidecar")
                    .build(),
            ).endVolume()
            // Cassandra data hostPath volume
            .addNewVolume()
            .withName("cassandra-data")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/cassandra")
                    .build(),
            ).endVolume()
            // OTel agent hostPath volume
            .addNewVolume()
            .withName("otel-agent")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/usr/local/otel")
                    .build(),
            ).endVolume()
            // Pyroscope agent hostPath volume
            .addNewVolume()
            .withName("pyroscope-agent")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/usr/local/pyroscope")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
