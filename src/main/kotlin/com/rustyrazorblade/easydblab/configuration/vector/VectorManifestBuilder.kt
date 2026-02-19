package com.rustyrazorblade.easydblab.configuration.vector

import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds all Vector K8s resources as typed Fabric8 objects.
 *
 * Creates two Vector deployments:
 * - **Node DaemonSet**: Runs on all nodes, collects system/Cassandra/ClickHouse logs
 *   and systemd journal. Sends to VictoriaLogs via control node IP.
 * - **S3 Deployment**: Runs on control plane, ingests EMR/Spark logs from S3 via SQS.
 *
 * Config uses Vector runtime env expansion (`${VICTORIA_LOGS_HOST}`, `${AWS_REGION}`,
 * `${SQS_QUEUE_URL}`), not `__KEY__` template substitution.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class VectorManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val IMAGE = "timberio/vector:0.34.1-alpine"

        private const val NODE_APP_LABEL = "vector"
        private const val NODE_CONFIGMAP_NAME = "vector-node-config"
        private const val NODE_COMPONENT = "node-logs"

        private const val S3_APP_LABEL = "vector-s3"
        private const val S3_CONFIGMAP_NAME = "vector-s3-config"
        private const val S3_COMPONENT = "s3-logs"

        private const val MEMORY_LIMIT = "256Mi"
        private const val MEMORY_REQUEST = "64Mi"
        private const val CPU_REQUEST = "50m"
    }

    /**
     * Builds all Vector K8s resources in apply order.
     *
     * @return List of: Node ConfigMap, Node DaemonSet, S3 ConfigMap, S3 Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildNodeConfigMap(),
            buildNodeDaemonSet(),
            buildS3ConfigMap(),
            buildS3Deployment(),
        )

    /**
     * Builds the Vector node-logs ConfigMap.
     */
    fun buildNodeConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(NODE_CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", NODE_APP_LABEL)
            .addToLabels("app.kubernetes.io/component", NODE_COMPONENT)
            .endMetadata()
            .addToData(
                "vector.yaml",
                templateService
                    .fromResource(
                        VectorManifestBuilder::class.java,
                        "vector-node.yaml",
                    ).substitute(),
            ).build()

    /**
     * Builds the Vector node-logs DaemonSet.
     *
     * Runs on all nodes with hostNetwork. Mounts system logs, journal,
     * machine-id, database logs, and a persistent data directory.
     */
    @Suppress("LongMethod")
    fun buildNodeDaemonSet() =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(NODE_APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", NODE_APP_LABEL)
            .addToLabels("app.kubernetes.io/component", NODE_COMPONENT)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels("app", NODE_APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app", NODE_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .addNewContainer()
            .withName(NODE_APP_LABEL)
            .withImage(IMAGE)
            .withArgs("--config", "/etc/vector/vector.yaml")
            .addNewEnv()
            .withName("HOSTNAME")
            .withNewValueFrom()
            .withNewFieldRef()
            .withFieldPath("spec.nodeName")
            .endFieldRef()
            .endValueFrom()
            .endEnv()
            .addNewEnv()
            .withName("VICTORIA_LOGS_HOST")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName("cluster-config")
            .withKey("control_node_ip")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            .withResources(
                ResourceRequirementsBuilder()
                    .addToLimits("memory", Quantity(MEMORY_LIMIT))
                    .addToRequests("memory", Quantity(MEMORY_REQUEST))
                    .addToRequests("cpu", Quantity(CPU_REQUEST))
                    .build(),
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/vector")
                    .build(),
                VolumeMountBuilder()
                    .withName("var-log")
                    .withMountPath("/var/log")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("journal")
                    .withMountPath("/var/log/journal")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("machine-id")
                    .withMountPath("/etc/machine-id")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("mnt-db1")
                    .withMountPath("/mnt/db1")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("data")
                    .withMountPath("/var/lib/vector")
                    .build(),
            ).endContainer()
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(NODE_CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("var-log")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/var/log")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("journal")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/var/log/journal")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("machine-id")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/etc/machine-id")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("mnt-db1")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("data")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/var/lib/vector")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    /**
     * Builds the Vector S3-logs ConfigMap.
     */
    fun buildS3ConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(S3_CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", NODE_APP_LABEL)
            .addToLabels("app.kubernetes.io/component", S3_COMPONENT)
            .endMetadata()
            .addToData(
                "vector.yaml",
                templateService
                    .fromResource(
                        VectorManifestBuilder::class.java,
                        "vector-s3.yaml",
                    ).substitute(),
            ).build()

    /**
     * Builds the Vector S3-logs Deployment.
     *
     * Runs on control plane with hostNetwork. Ingests EMR/Spark logs
     * from S3 via SQS notifications.
     */
    @Suppress("LongMethod")
    fun buildS3Deployment() =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(S3_APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", NODE_APP_LABEL)
            .addToLabels("app.kubernetes.io/component", S3_COMPONENT)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels("app", S3_APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app", S3_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addToNodeSelector("node-role.kubernetes.io/control-plane", "true")
            .addNewToleration()
            .withKey("node-role.kubernetes.io/control-plane")
            .withOperator("Exists")
            .withEffect("NoSchedule")
            .endToleration()
            .addNewContainer()
            .withName(NODE_APP_LABEL)
            .withImage(IMAGE)
            .withArgs("--config", "/etc/vector/vector.yaml")
            .addNewEnv()
            .withName("AWS_REGION")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName("cluster-config")
            .withKey("aws_region")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            .addNewEnv()
            .withName("SQS_QUEUE_URL")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName("cluster-config")
            .withKey("sqs_queue_url")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            .withResources(
                ResourceRequirementsBuilder()
                    .addToLimits("memory", Quantity(MEMORY_LIMIT))
                    .addToRequests("memory", Quantity(MEMORY_REQUEST))
                    .addToRequests("cpu", Quantity(CPU_REQUEST))
                    .build(),
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/vector")
                    .build(),
                VolumeMountBuilder()
                    .withName("data")
                    .withMountPath("/var/lib/vector")
                    .build(),
            ).endContainer()
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(S3_CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("data")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/var/lib/vector-s3")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
