package com.rustyrazorblade.easydblab.configuration.tempo

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.BackendEndpoints
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.ServicePublishing
import com.rustyrazorblade.easydblab.configuration.StateVolume
import com.rustyrazorblade.easydblab.configuration.clusterConfigEnv
import com.rustyrazorblade.easydblab.configuration.store.StoreBlockRenderer
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds all Tempo K8s resources as typed Fabric8 objects.
 *
 * Creates a Deployment on the control plane with S3 backend for trace storage, under
 * `tempo` in the account bucket. Tempo runs native multi-tenancy and never deletes a
 * block. Its write-ahead logs live on a hostPath under [DATA_HOST_PATH], so a pod restart keeps
 * received spans. Config uses Tempo runtime env expansion (`${STORE_BUCKET}`, `${STORE_ENDPOINT}`,
 * `${TRACES_STORE_PREFIX}`), not `__KEY__` template substitution. Env vars are injected from the
 * cluster-config ConfigMap.
 *
 * @property templateService Used for loading config files from classpath resources
 * @property storeBlocks Places the object-store block of the cluster's store ([TemplateService.storeBackend]) into the config.
 */
class TempoManifestBuilder(
    private val templateService: TemplateService,
    private val storeBlocks: StoreBlockRenderer = StoreBlockRenderer.of(templateService::storeBackend),
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = Constants.K8s.TEMPO_APP_LABEL
        private const val CONFIGMAP_NAME = "tempo-config"
        const val IMAGE = "grafana/tempo:3.0.3"

        /** The ConfigMap key and resource name of Tempo's config file. */
        const val CONFIG_FILE = "tempo.yaml"

        /** Tempo's data directory on the control node: both write-ahead logs live under it. */
        const val DATA_HOST_PATH = "/mnt/db1/tempo"

        private val STATE = StateVolume(APP_LABEL, "wal", DATA_HOST_PATH, owner = TEMPO_UID)

        /** The user the Tempo image runs as; [DATA_HOST_PATH] must be owned by it. */
        const val TEMPO_UID = 10001L

        private const val LIVENESS_INITIAL_DELAY = 30
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10

        // Tempo is not ready until its first blocklist poll ends, and a shared bucket with many
        // blocks takes minutes (86 s for 1,394 blocks and 130,692 compacted metas).  The startup
        // probe holds the liveness probe off for up to 10 minutes.  `/ready` can answer slowly
        // while the poll runs, so each check waits 5 s instead of the 1 s default.
        private const val STARTUP_PERIOD = 10
        private const val STARTUP_TIMEOUT = 5
        private const val STARTUP_FAILURE_THRESHOLD = 60
    }

    /**
     * Builds all Tempo K8s resources in apply order.
     *
     * @param hosting where the Deployment is placed and keeps its state.
     * @return List of: ConfigMap, Service, the state claim when [hosting] uses one, Deployment
     */
    fun buildAllResources(hosting: ObservabilityHosting = ObservabilityHosting.AWS): List<HasMetadata> =
        listOf(buildConfigMap(), buildService(hosting.publishing)) + hosting.host(buildPodTemplate(), STATE, isBackend = true)

    /** The Tempo Deployment as [hosting] runs it. */
    fun buildDeployment(hosting: ObservabilityHosting = ObservabilityHosting.AWS): Deployment =
        hosting.host(buildPodTemplate(), STATE, isBackend = true).filterIsInstance<Deployment>().single()

    /**
     * Builds the Tempo ConfigMap containing tempo.yaml.
     */
    fun buildConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                CONFIG_FILE,
                templateService
                    .fromString(storeBlocks.renderResource(TempoManifestBuilder::class.java, CONFIG_FILE))
                    .substitute(
                        mapOf(
                            "MIMIR_HOST" to BackendEndpoints.mimirHost(),
                            "MIMIR_HTTP_PORT" to Constants.K8s.MIMIR_HTTP_PORT.toString(),
                        ),
                    ),
            ).build()

    /**
     * Tempo's Service over HTTP, OTLP gRPC and OTLP HTTP; [publishing] puts the query port on
     * [Constants.K8s.TEMPO_NODE_PORT] and OTLP gRPC on [Constants.K8s.TEMPO_OTLP_GRPC_NODE_PORT].
     */
    fun buildService(publishing: ServicePublishing = ServicePublishing.ClusterIp): Service =
        publishing.publish(
            clusterIpService(),
            mapOf(
                "http" to Constants.K8s.TEMPO_NODE_PORT,
                "otlp-grpc" to Constants.K8s.TEMPO_OTLP_GRPC_NODE_PORT,
            ),
        )

    private fun clusterIpService(): Service =
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
            .withPort(Constants.K8s.TEMPO_PORT)
            .withNewTargetPort(Constants.K8s.TEMPO_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("otlp-grpc")
            .withPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
            .withNewTargetPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("otlp-http")
            .withPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
            .withNewTargetPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the Tempo Deployment before it is hosted. [ObservabilityHosting] places it and sets
     * where its WAL volume lives; callers outside the pod network reach its query and OTLP
     * gRPC ports through its Service's NodePorts on nodes easy-db-lab owns. Uses env vars from cluster-config
     * ConfigMap for S3 bucket and AWS region, with `-config.expand-env=true` to
     * enable Tempo's runtime env expansion in the config file.
     */
    @Suppress("LongMethod")
    private fun buildPodTemplate(): Deployment =
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
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withArgs(
                "-config.file=/etc/tempo/$CONFIG_FILE",
                "-config.expand-env=true",
            ).withEnv(
                clusterConfigEnv("STORE_BUCKET", "store_bucket"),
                clusterConfigEnv("STORE_REGION", "store_region"),
                clusterConfigEnv("STORE_ENDPOINT", "store_endpoint"),
                clusterConfigEnv("TRACES_STORE_PREFIX", "traces_store_prefix"),
                clusterConfigEnv("CLUSTER_NAME", "cluster_name"),
            ).addNewPort()
            .withContainerPort(Constants.K8s.TEMPO_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/tempo")
                    .build(),
                VolumeMountBuilder()
                    .withName("wal")
                    .withMountPath("/var/tempo")
                    .build(),
            ).withNewStartupProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(Constants.K8s.TEMPO_PORT)
            .endHttpGet()
            .withPeriodSeconds(STARTUP_PERIOD)
            .withTimeoutSeconds(STARTUP_TIMEOUT)
            .withFailureThreshold(STARTUP_FAILURE_THRESHOLD)
            .endStartupProbe()
            .withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(Constants.K8s.TEMPO_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(Constants.K8s.TEMPO_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .endContainer()
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName(STATE.volumeName)
            .endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
