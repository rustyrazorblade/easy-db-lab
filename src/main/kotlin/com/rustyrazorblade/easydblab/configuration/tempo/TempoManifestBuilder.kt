package com.rustyrazorblade.easydblab.configuration.tempo

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.controlPlaneDeployment
import com.rustyrazorblade.easydblab.configuration.hostNetworked
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder

/**
 * Builds all Tempo K8s resources as typed Fabric8 objects.
 *
 * Creates a Deployment on the control plane with S3 backend for trace storage, under
 * `tempo` in the account bucket. Tempo runs native multi-tenancy and never deletes a
 * block. Its write-ahead logs live on a hostPath under [DATA_HOST_PATH], so a pod restart keeps
 * received spans. Config uses Tempo runtime env expansion (`${S3_BUCKET}`, `${AWS_REGION}`,
 * `${TRACES_S3_PREFIX}`), not `__KEY__` template substitution. Env vars are injected from the
 * cluster-config ConfigMap.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class TempoManifestBuilder(
    private val templateService: TemplateService,
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

        /** The user the Tempo image runs as; [DATA_HOST_PATH] must be owned by it. */
        const val TEMPO_UID = 10001L

        /**
         * Tempo's memory limit, the one exception to the no-limits rule for control-node pods.
         *
         * Tempo sets GOMEMLIMIT from it (`memory.automemlimit_enabled` in tempo.yaml), so the Go
         * collector works to stay under it, and the pod is no longer BestEffort, the first pod the
         * kernel kills. With live traces capped at 500 MB, Tempo peaked at 5.1 GB under an ingest
         * rate past what it could drain. 6 GiB leaves the rest of the 16 GiB default control node
         * to K3s and the other backends.
         */
        const val MEMORY_LIMIT = "6Gi"

        /** Tempo's steady-state memory, so the scheduler reserves it: 1.1-1.7 GB at 42k spans/s. */
        const val MEMORY_REQUEST = "2Gi"

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
     * @return List of: ConfigMap, Service, Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildConfigMap(),
            buildService(),
            buildDeployment(),
        )

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
                    .fromResource(
                        TempoManifestBuilder::class.java,
                        CONFIG_FILE,
                    ).substitute(),
            ).build()

    /**
     * Builds the Tempo ClusterIP Service with HTTP, OTLP gRPC, and OTLP HTTP ports.
     */
    fun buildService() =
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
     * Builds the Tempo Deployment.
     *
     * Runs on control plane with hostNetwork. Uses env vars from cluster-config
     * ConfigMap for S3 bucket and AWS region, with `-config.expand-env=true` to
     * enable Tempo's runtime env expansion in the config file.
     */
    @Suppress("LongMethod")
    fun buildDeployment() =
        controlPlaneDeployment(APP_LABEL, NAMESPACE) {
            hostNetworked()
                .addNewContainer()
                .withName(APP_LABEL)
                .withImage(IMAGE)
                .withArgs(
                    "-config.file=/etc/tempo/$CONFIG_FILE",
                    "-config.expand-env=true",
                ).addNewEnv()
                .withName("AWS_REGION")
                .withNewValueFrom()
                .withNewConfigMapKeyRef()
                .withName("cluster-config")
                .withKey("aws_region")
                .endConfigMapKeyRef()
                .endValueFrom()
                .endEnv()
                .addNewEnv()
                .withName("S3_BUCKET")
                .withNewValueFrom()
                .withNewConfigMapKeyRef()
                .withName("cluster-config")
                .withKey("s3_bucket")
                .endConfigMapKeyRef()
                .endValueFrom()
                .endEnv()
                .addNewEnv()
                .withName("TRACES_S3_PREFIX")
                .withNewValueFrom()
                .withNewConfigMapKeyRef()
                .withName("cluster-config")
                .withKey("traces_s3_prefix")
                .endConfigMapKeyRef()
                .endValueFrom()
                .endEnv()
                .addNewEnv()
                .withName("CLUSTER_NAME")
                .withNewValueFrom()
                .withNewConfigMapKeyRef()
                .withName("cluster-config")
                .withKey("cluster_name")
                .endConfigMapKeyRef()
                .endValueFrom()
                .endEnv()
                .addNewPort()
                .withContainerPort(Constants.K8s.TEMPO_PORT)
                .withHostPort(Constants.K8s.TEMPO_PORT)
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withContainerPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
                .withHostPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withContainerPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
                .withHostPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
                .withProtocol("TCP")
                .endPort()
                .withNewResources()
                .addToRequests("memory", Quantity(MEMORY_REQUEST))
                .addToLimits("memory", Quantity(MEMORY_LIMIT))
                .endResources()
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
                .withName("wal")
                .withHostPath(
                    HostPathVolumeSourceBuilder()
                        .withPath(DATA_HOST_PATH)
                        .withType("DirectoryOrCreate")
                        .build(),
                ).endVolume()
        }
}
