package com.rustyrazorblade.easydblab.configuration.tempo

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

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

        /** Tempo's data directory on the control node: both write-ahead logs live under it. */
        const val DATA_HOST_PATH = "/mnt/db1/tempo"

        /** The user the Tempo image runs as; [DATA_HOST_PATH] must be owned by it. */
        const val TEMPO_UID = 10001L

        /**
         * Tempo's memory request and limit: 8 GiB of the control node's 16 GiB (m5d.xlarge). The
         * live-trace brake and the Go runtime's soft memory limit are both derived from it.
         *
         * Tempo is the one builder that sets a request and limit. Its live store grows with
         * ingest, and with no bound it grew to about 10 GB until the kernel killed it; the owner
         * chose a hard limit with a brake under it.
         */
        val MEMORY: Quantity = Quantity("8Gi")

        /** [MEMORY] in bytes, the base the brake and `GOMEMLIMIT` are computed from. */
        val MEMORY_BYTES: Long = Quantity.getAmountInBytes(MEMORY).toLong()

        /**
         * `live_store.max_live_traces_bytes`: past this many bytes of live traces, Tempo stalls
         * each push a second at a time until the live store cuts traces to the WAL.
         *
         * Why an eighth, not half: the brake counts only proto bytes in the live map, and Tempo
         * 3.0.3 subtracts a cut batch from that count before it writes the batch. So the live map
         * and the batch being written can each hold this much at once (2x). Both are decoded Go
         * objects, larger than their proto bytes (about 2x). The Go collector lets the heap grow
         * past the live heap before it runs (up to 2x, bounded by [GO_MEM_LIMIT_BYTES]). That is
         * 8x, before the head block and queries, so an eighth of [MEMORY_BYTES] keeps the worst
         * case at the limit rather than past it.
         */
        val MAX_LIVE_TRACES_BYTES: Long = MEMORY_BYTES / 8

        /**
         * `GOMEMLIMIT`: the Go runtime collects garbage harder as the heap nears this, instead of
         * letting it grow to twice the live heap. A tenth of [MEMORY_BYTES] is left for memory
         * the Go runtime does not count.
         */
        val GO_MEM_LIMIT_BYTES: Long = MEMORY_BYTES * 9 / 10

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

    /** A memory request and limit of [MEMORY]; no CPU request or limit. */
    private fun memoryRequirements() =
        ResourceRequirementsBuilder()
            .addToRequests("memory", MEMORY)
            .addToLimits("memory", MEMORY)
            .build()

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
                "tempo.yaml",
                templateService
                    .fromResource(
                        TempoManifestBuilder::class.java,
                        "tempo.yaml",
                    ).substitute(mapOf("TEMPO_MAX_LIVE_TRACES_BYTES" to "$MAX_LIVE_TRACES_BYTES")),
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
                "-config.file=/etc/tempo/tempo.yaml",
                "-config.expand-env=true",
            ).withResources(memoryRequirements())
            .addNewEnv()
            .withName("GOMEMLIMIT")
            .withValue("$GO_MEM_LIMIT_BYTES")
            .endEnv()
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
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
