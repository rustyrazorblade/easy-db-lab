package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder

/**
 * Builds all OpenTelemetry Collector K8s resources as typed Fabric8 objects.
 *
 * Creates a DaemonSet that runs on all nodes with hostNetwork, collecting
 * host metrics, Prometheus scrapes (ClickHouse, Vector, Beyla, ebpf_exporter),
 * file-based logs, and OTLP. Exports to VictoriaMetrics, VictoriaLogs, and Tempo.
 *
 * Config uses OTel runtime env expansion (`${env:HOSTNAME}`, `${env:CLUSTER_NAME}`),
 * not `__KEY__` template substitution.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class OtelManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "otel-collector"
        private const val CONFIGMAP_NAME = "otel-collector-config"
        private const val CONFIG_DATA_KEY = "otel-collector-config.yaml"
        private const val IMAGE = "otel/opentelemetry-collector-contrib:latest"
        private const val LIVENESS_INITIAL_DELAY = 10
        private const val LIVENESS_PERIOD = 30
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all OTel Collector K8s resources in apply order.
     *
     * @return List of: ConfigMap, DaemonSet
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildConfigMap(),
            buildDaemonSet(),
        )

    /**
     * Builds the OTel Collector ConfigMap containing the collector config.
     */
    fun buildConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                CONFIG_DATA_KEY,
                templateService
                    .fromResource(
                        OtelManifestBuilder::class.java,
                        "otel-collector-config.yaml",
                    ).substitute(),
            ).build()

    /**
     * Builds the OTel Collector DaemonSet.
     *
     * Runs on all nodes with hostNetwork, privileged mode.
     * Mounts ClickHouse log directories as hostPath volumes (DirectoryOrCreate).
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
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withSecurityContext(
                SecurityContextBuilder()
                    .withPrivileged(true)
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
                    .build(),
            ).withArgs("--config=/etc/otel-collector-config.yaml")
            .addNewPort()
            .withContainerPort(Constants.K8s.OTEL_GRPC_PORT)
            .withHostPort(Constants.K8s.OTEL_GRPC_PORT)
            .withProtocol("TCP")
            .withName("otlp-grpc")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.OTEL_HTTP_PORT)
            .withHostPort(Constants.K8s.OTEL_HTTP_PORT)
            .withProtocol("TCP")
            .withName("otlp-http")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.OTEL_HEALTH_PORT)
            .withHostPort(Constants.K8s.OTEL_HEALTH_PORT)
            .withProtocol("TCP")
            .withName("health")
            .endPort()
            .addNewEnv()
            .withName("HOSTNAME")
            .withNewValueFrom()
            .withNewFieldRef()
            .withFieldPath("spec.nodeName")
            .endFieldRef()
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
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/otel-collector-config.yaml")
                    .withSubPath(CONFIG_DATA_KEY)
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("clickhouse-server-logs")
                    .withMountPath("/mnt/db1/clickhouse/logs")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("clickhouse-keeper-logs")
                    .withMountPath("/mnt/db1/clickhouse/keeper/logs")
                    .withReadOnly(true)
                    .build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(Constants.K8s.OTEL_HEALTH_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(Constants.K8s.OTEL_HEALTH_PORT)
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
            .withName("clickhouse-server-logs")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/clickhouse/logs")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("clickhouse-keeper-logs")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/clickhouse/keeper/logs")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
