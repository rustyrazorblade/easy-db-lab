package com.rustyrazorblade.easydblab.configuration.loki

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.ServicePublishing
import com.rustyrazorblade.easydblab.configuration.StateVolume
import com.rustyrazorblade.easydblab.configuration.clusterConfigEnv
import com.rustyrazorblade.easydblab.configuration.store.StoreBlockRenderer
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.services.ClusterConfigData
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds Loki's K8s resources as typed Fabric8 objects: the logs backend on the control node.
 *
 * Loki runs as a single binary (`target: all`), the only single-process target that both writes and
 * reads the S3 index, with its compactor idle: compaction pushed ten years out, retention off and
 * deletion disabled. Chunks and index files land under `loki/` in the account bucket.
 * The WAL, the TSDB index head, the uploader's name and the compactor's working directory live on
 * the hostPath [DATA_HOST_PATH], so a pod restart replays the WAL and a later upload keeps its file
 * names; the long grace period gives a graceful stop time to flush every chunk.
 *
 * `loki.yaml` is a classpath resource read with `-config.expand-env=true`: the bucket, region,
 * prefix, tenant and cluster name come from the cluster-config ConfigMap ([ClusterConfigData]). The
 * ingester is named `<tenant>.<cluster>`, which puts both in every index file name.
 *
 * @property templateService Loads the config file from the classpath.
 * @property storeBlocks Places the object-store block of the cluster's store ([TemplateService.storeBackend]) into the config.
 */
class LokiManifestBuilder(
    private val templateService: TemplateService,
    private val storeBlocks: StoreBlockRenderer = StoreBlockRenderer.of(templateService::storeBackend),
) {
    companion object {
        private const val APP_LABEL = Constants.K8s.LOKI_APP_LABEL
        private const val CONFIGMAP_NAME = "loki-config"
        private const val CONFIG_MOUNT_PATH = "/etc/loki"
        private const val DATA_MOUNT_PATH = "/loki"

        /** The key of the config file in the ConfigMap. */
        const val CONFIG_FILE = "loki.yaml"

        /** The Loki image the cluster deploys; tests run the same one. */
        const val IMAGE = "grafana/loki:3.7.8"

        /** Loki's data directory on the control node: WAL, index head, index cache, compactor. */
        const val DATA_HOST_PATH = "/mnt/db1/loki"

        private val STATE = StateVolume(APP_LABEL, "data", DATA_HOST_PATH, owner = LOKI_UID)

        /** The user the Loki image runs as; [DATA_HOST_PATH] must be owned by it. */
        const val LOKI_UID = 10001L

        /** Time a graceful stop gets to flush every chunk in memory and upload the index. */
        const val TERMINATION_GRACE_SECONDS = 600L

        private const val LIVENESS_INITIAL_DELAY = 30
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all Loki K8s resources in apply order.
     *
     * @param hosting where the Deployment is placed and keeps its state.
     * @return ConfigMap, Service, the state claim when [hosting] uses one, Deployment
     */
    fun buildAllResources(
        scope: InstallScope,
        hosting: ObservabilityHosting = ObservabilityHosting.AWS,
    ): List<HasMetadata> =
        listOf(buildConfigMap(scope), buildService(scope, hosting.publishing)) +
            hosting.host(buildPodTemplate(scope), STATE, isBackend = true)

    /** The Loki Deployment as [hosting] runs it. */
    fun buildDeployment(
        scope: InstallScope,
        hosting: ObservabilityHosting = ObservabilityHosting.AWS,
    ): Deployment = hosting.host(buildPodTemplate(scope), STATE, isBackend = true).filterIsInstance<Deployment>().single()

    /** The ConfigMap holding `loki.yaml`. */
    fun buildConfigMap(scope: InstallScope): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(scope.namespace.value)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                CONFIG_FILE,
                templateService.fromString(storeBlocks.renderResource(LokiManifestBuilder::class.java, CONFIG_FILE)).substitute(),
            ).build()

    /** Loki's Service over HTTP and gRPC; [publishing] puts the HTTP port on [Constants.K8s.LOKI_NODE_PORT]. */
    fun buildService(
        scope: InstallScope,
        publishing: ServicePublishing = ServicePublishing.ClusterIp,
    ): Service =
        publishing.publish(
            clusterIpService(scope),
            mapOf(
                "http" to Constants.K8s.LOKI_NODE_PORT,
            ),
        )

    private fun clusterIpService(scope: InstallScope): Service =
        ServiceBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(scope.namespace.value)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
            .withName("http")
            .withPort(Constants.K8s.LOKI_HTTP_PORT)
            .withNewTargetPort(Constants.K8s.LOKI_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("grpc")
            .withPort(Constants.K8s.LOKI_GRPC_PORT)
            .withNewTargetPort(Constants.K8s.LOKI_GRPC_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", APP_LABEL)
            .endSpec()
            .build()

    /**
     * The Loki Deployment before it is hosted: one replica, `Recreate` so two processes never share
     * the WAL, and no resource limits. [ObservabilityHosting] places it and sets where its state
     * volume lives. Callers outside the pod network reach its HTTP port through its
     * Service's NodePort on nodes easy-db-lab owns.
     *
     * Liveness only checks that the HTTP server answers: readiness waits for the WAL replay, which
     * can outlast any liveness budget after a restart.
     */
    @Suppress("LongMethod")
    private fun buildPodTemplate(scope: InstallScope): Deployment =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(scope.namespace.value)
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
            .withTerminationGracePeriodSeconds(TERMINATION_GRACE_SECONDS)
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withArgs(
                "-config.file=$CONFIG_MOUNT_PATH/$CONFIG_FILE",
                "-config.expand-env=true",
            ).withEnv(
                clusterConfigEnv("STORE_BUCKET", "store_bucket"),
                clusterConfigEnv("STORE_REGION", "store_region"),
                clusterConfigEnv("STORE_ENDPOINT", "store_endpoint"),
                clusterConfigEnv("LOGS_STORE_PREFIX", "logs_store_prefix"),
                clusterConfigEnv("TENANT", "tenant"),
                clusterConfigEnv("CLUSTER_NAME", "cluster_name"),
            ).addNewPort()
            .withName("http")
            .withContainerPort(Constants.K8s.LOKI_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("grpc")
            .withContainerPort(Constants.K8s.LOKI_GRPC_PORT)
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
            .withNewPort(Constants.K8s.LOKI_HTTP_PORT)
            .endTcpSocket()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(Constants.K8s.LOKI_HTTP_PORT)
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
            .withName(STATE.volumeName)
            .endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
