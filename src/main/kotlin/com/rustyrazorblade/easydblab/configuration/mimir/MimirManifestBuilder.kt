package com.rustyrazorblade.easydblab.configuration.mimir

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.clusterConfigEnv
import com.rustyrazorblade.easydblab.services.ClusterConfigData
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds Mimir's K8s resources as typed Fabric8 objects: the metrics backend on the control node.
 *
 * Mimir runs monolithic (distributor, ingester, querier, query-frontend, query-scheduler) with no
 * compactor and no store-gateway. It ships every block to `observabilitymetrics/<tenant>/` in the
 * account bucket and answers every query from its own ingester, so nothing in a cluster can compact
 * or delete metrics and no query reads the S3 block store. The TSDB (head, WAL and local blocks)
 * lives on the hostPath [DATA_HOST_PATH], so a pod restart replays the WAL and keeps every block;
 * the long grace period gives a graceful stop time to compact and ship the head.
 *
 * `mimir.yaml` is a classpath resource read with `-config.expand-env=true`: the bucket, region and
 * storage prefix come from the cluster-config ConfigMap ([ClusterConfigData]) as env vars.
 *
 * @property templateService Loads the config file from the classpath.
 */
class MimirManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = Constants.K8s.NAMESPACE
        private const val APP_LABEL = Constants.K8s.MIMIR_APP_LABEL
        private const val CONFIGMAP_NAME = "mimir-config"
        private const val CONFIG_MOUNT_PATH = "/etc/mimir"
        private const val DATA_MOUNT_PATH = "/data"

        /** The key of the config file in the ConfigMap. */
        const val CONFIG_FILE = "mimir.yaml"

        /** The Mimir image the cluster deploys; tests run the same one. */
        const val IMAGE = "grafana/mimir:3.2.1"

        /** Mimir's data directory on the control node: the TSDB, its WAL and every local block. */
        const val DATA_HOST_PATH = "/mnt/db1/mimir"

        /**
         * Time a graceful stop gets to compact the head into a block and ship it
         * (`flush_blocks_on_shutdown`); Kubernetes' 30s default would kill it mid-flush.
         */
        const val TERMINATION_GRACE_SECONDS = 600L

        private const val LIVENESS_INITIAL_DELAY = 30
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all Mimir K8s resources in apply order.
     *
     * @return ConfigMap, Service, Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildConfigMap(),
            buildService(),
            buildDeployment(),
        )

    /** The ConfigMap holding `mimir.yaml`. */
    fun buildConfigMap(): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                CONFIG_FILE,
                templateService.fromResource(MimirManifestBuilder::class.java, CONFIG_FILE).substitute(),
            ).build()

    /** The ClusterIP Service over Mimir's HTTP and gRPC ports. */
    fun buildService(): Service =
        ServiceBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
            .withName("http")
            .withPort(Constants.K8s.MIMIR_HTTP_PORT)
            .withNewTargetPort(Constants.K8s.MIMIR_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("grpc")
            .withPort(Constants.K8s.MIMIR_GRPC_PORT)
            .withNewTargetPort(Constants.K8s.MIMIR_GRPC_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", APP_LABEL)
            .endSpec()
            .build()

    /**
     * The Mimir Deployment: one replica on the control node, host network, `Recreate` so two
     * ingesters never share the TSDB directory, and no resource limits.
     *
     * Liveness only checks that the HTTP server answers: readiness waits for the WAL replay, which
     * can outlast any liveness budget after a restart with a large head.
     */
    @Suppress("LongMethod")
    fun buildDeployment(): Deployment =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", APP_LABEL)
            .endSelector()
            .withNewStrategy()
            .withType("Recreate")
            .withRollingUpdate(null)
            .endStrategy()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .withTerminationGracePeriodSeconds(TERMINATION_GRACE_SECONDS)
            .addToNodeSelector("node-role.kubernetes.io/control-plane", "true")
            .addNewToleration()
            .withKey("node-role.kubernetes.io/control-plane")
            .withOperator("Exists")
            .withEffect("NoSchedule")
            .endToleration()
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withArgs(
                "-config.file=$CONFIG_MOUNT_PATH/$CONFIG_FILE",
                "-config.expand-env=true",
            ).withEnv(
                clusterConfigEnv("AWS_REGION", "aws_region"),
                clusterConfigEnv("S3_BUCKET", "s3_bucket"),
                clusterConfigEnv("METRICS_S3_PREFIX", "metrics_s3_prefix"),
            ).addNewPort()
            .withName("http")
            .withContainerPort(Constants.K8s.MIMIR_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("grpc")
            .withContainerPort(Constants.K8s.MIMIR_GRPC_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath(CONFIG_MOUNT_PATH)
                    .build(),
                VolumeMountBuilder()
                    .withName("data")
                    .withMountPath(DATA_MOUNT_PATH)
                    .build(),
            ).withNewLivenessProbe()
            .withNewTcpSocket()
            .withNewPort(Constants.K8s.MIMIR_HTTP_PORT)
            .endTcpSocket()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(Constants.K8s.MIMIR_HTTP_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .endContainer()
            .addNewVolume()
            .withName("config")
            .withConfigMap(ConfigMapVolumeSourceBuilder().withName(CONFIGMAP_NAME).build())
            .endVolume()
            .addNewVolume()
            .withName("data")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath(DATA_HOST_PATH)
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
