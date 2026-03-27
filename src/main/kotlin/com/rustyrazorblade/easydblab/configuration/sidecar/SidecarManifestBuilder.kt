package com.rustyrazorblade.easydblab.configuration.sidecar

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Constants.Cassandra
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder

/**
 * Builds K8s resources for the Cassandra sidecar DaemonSet.
 *
 * Deploys one sidecar pod per database node (nodeSelector: type=db) with hostNetwork
 * so the sidecar can connect to the local Cassandra instance on localhost:9042.
 *
 * An init container (busybox) reads the node's private IP from the Kubernetes Downward
 * API (status.hostIP) and substitutes the __HOST_IP__ placeholder in the config template,
 * writing the result to a shared emptyDir volume mounted at /conf/.
 *
 * Pyroscope Java agent is injected via JAVA_TOOL_OPTIONS using the agent JAR from the
 * host at /usr/local/pyroscope (installed by packer on all Cassandra nodes).
 *
 * @property templateService Used for loading config template from classpath
 */
class SidecarManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        const val APP_LABEL = "cassandra-sidecar"
        const val CONFIGMAP_NAME = "cassandra-sidecar-config"
        private const val CONFIG_DATA_KEY = "cassandra-sidecar.yaml"
        private const val DEFAULT_IMAGE = "ghcr.io/apache/cassandra-sidecar:latest"
        private const val PYROSCOPE_HOST_PATH = "/usr/local/pyroscope"
        private const val PYROSCOPE_MOUNT_PATH = "/usr/local/pyroscope"
        private const val CASSANDRA_DATA_PATH = "/mnt/db1/cassandra"

        private const val SIDECAR_CONFIG_PATH = "/conf/sidecar.yaml"
    }

    /**
     * Builds all sidecar K8s resources in apply order.
     *
     * @param image Container image for the sidecar (default: ghcr.io/apache/cassandra-sidecar:latest)
     * @param controlNodeIp Private IP of the control node (for Pyroscope server address)
     * @param clusterName Cluster name (for Pyroscope labels)
     * @return List of: ConfigMap, DaemonSet
     */
    fun buildAllResources(
        image: String = DEFAULT_IMAGE,
        controlNodeIp: String,
        clusterName: String,
    ): List<HasMetadata> =
        listOf(
            buildConfigMap(),
            buildDaemonSet(image, controlNodeIp, clusterName),
        )

    /**
     * Builds the ConfigMap containing the sidecar config template.
     * The __HOST_IP__ placeholder is substituted by the init container at pod startup.
     */
    fun buildConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(Constants.K8s.NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                CONFIG_DATA_KEY,
                templateService
                    .fromResource(
                        SidecarManifestBuilder::class.java,
                        CONFIG_DATA_KEY,
                    ).substitute(),
            ).build()

    /**
     * Builds the sidecar DaemonSet.
     *
     * Runs on db nodes only (nodeSelector: type=db) with hostNetwork for Cassandra access.
     * Init container substitutes __HOST_IP__ from the Downward API into the config template.
     * Main container runs as cassandra user (UID 999) with Pyroscope and OTel instrumentation.
     */
    @Suppress("LongMethod")
    fun buildDaemonSet(
        image: String,
        controlNodeIp: String,
        clusterName: String,
    ) = DaemonSetBuilder()
        .withNewMetadata()
        .withName(APP_LABEL)
        .withNamespace(Constants.K8s.NAMESPACE)
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
        .addToNodeSelector("type", "db")
        .withHostNetwork(true)
        .withDnsPolicy("ClusterFirstWithHostNet")
        // Init container: substitute __HOST_IP__ with the node's actual IP
        .addNewInitContainer()
        .withName("config-init")
        .withImage("busybox:latest")
        .withCommand("sh", "-c")
        .withArgs(
            "sed \"s/__HOST_IP__/\$HOST_IP/g\" /config-template/$CONFIG_DATA_KEY > /conf/sidecar.yaml",
        ).addNewEnv()
        .withName("HOST_IP")
        .withNewValueFrom()
        .withNewFieldRef()
        .withFieldPath("status.hostIP")
        .endFieldRef()
        .endValueFrom()
        .endEnv()
        .addToVolumeMounts(
            VolumeMountBuilder()
                .withName("config-template")
                .withMountPath("/config-template")
                .withReadOnly(true)
                .build(),
            VolumeMountBuilder()
                .withName("config")
                .withMountPath("/conf")
                .build(),
        ).endInitContainer()
        // Main container: sidecar with Pyroscope and OTel
        .addNewContainer()
        .withName(APP_LABEL)
        .withImage(image)
        .withSecurityContext(
            SecurityContextBuilder()
                .withRunAsUser(Cassandra.USER_ID)
                .withRunAsGroup(Cassandra.USER_ID)
                .build(),
        ).addNewEnv()
        .withName("NODE_NAME")
        .withNewValueFrom()
        .withNewFieldRef()
        .withFieldPath("spec.nodeName")
        .endFieldRef()
        .endValueFrom()
        .endEnv()
        .addNewEnv()
        .withName("JAVA_TOOL_OPTIONS")
        .withValue(buildJavaToolOptions(controlNodeIp, clusterName))
        .endEnv()
        .addNewEnv()
        .withName("OTEL_EXPORTER_OTLP_ENDPOINT")
        .withValue("http://localhost:${Constants.K8s.OTEL_GRPC_PORT}")
        .endEnv()
        .addNewEnv()
        .withName("OTEL_SERVICE_NAME")
        .withValue(APP_LABEL)
        .endEnv()
        .addToVolumeMounts(
            VolumeMountBuilder()
                .withName("config")
                .withMountPath("/conf")
                .withReadOnly(true)
                .build(),
            VolumeMountBuilder()
                .withName("cassandra-data")
                .withMountPath(CASSANDRA_DATA_PATH)
                .build(),
            VolumeMountBuilder()
                .withName("pyroscope-agent")
                .withMountPath(PYROSCOPE_MOUNT_PATH)
                .withReadOnly(true)
                .build(),
        ).endContainer()
        // Volumes
        .addNewVolume()
        .withName("config-template")
        .withConfigMap(
            ConfigMapVolumeSourceBuilder()
                .withName(CONFIGMAP_NAME)
                .build(),
        ).endVolume()
        .addNewVolume()
        .withName("config")
        .withEmptyDir(EmptyDirVolumeSourceBuilder().build())
        .endVolume()
        .addNewVolume()
        .withName("cassandra-data")
        .withHostPath(
            HostPathVolumeSourceBuilder()
                .withPath(CASSANDRA_DATA_PATH)
                .withType("DirectoryOrCreate")
                .build(),
        ).endVolume()
        .addNewVolume()
        .withName("pyroscope-agent")
        .withHostPath(
            HostPathVolumeSourceBuilder()
                .withPath(PYROSCOPE_HOST_PATH)
                .withType("Directory")
                .build(),
        ).endVolume()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()

    private fun buildJavaToolOptions(
        controlNodeIp: String,
        clusterName: String,
    ): String {
        val pyroscopeServerAddress = "http://$controlNodeIp:${Constants.K8s.PYROSCOPE_PORT}"
        return listOf(
            "-Dsidecar.config=file://$SIDECAR_CONFIG_PATH",
            "-javaagent:$PYROSCOPE_MOUNT_PATH/pyroscope.jar",
            "-Dpyroscope.application.name=$APP_LABEL",
            "-Dpyroscope.server.address=$pyroscopeServerAddress",
            "-Dpyroscope.labels=hostname:\$(NODE_NAME),cluster:$clusterName",
            "-Dpyroscope.profiler.event=cpu",
            "-Dpyroscope.profiler.alloc=512k",
            "-Dpyroscope.profiler.lock=10ms",
        ).joinToString(" ")
    }
}
