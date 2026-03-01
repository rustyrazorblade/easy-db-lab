package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder

/**
 * Builds K8s resources for a dedicated OTel collector that reads systemd journal entries.
 *
 * Isolated from the main OTel collector so journald receiver failures (alpha stability)
 * don't affect existing log/metric/trace collection. Forwards logs via OTLP to the
 * main collector on localhost:4317.
 *
 * Uses chroot mode (`root_path: /host`) to execute the host's journalctl binary,
 * avoiding the need for a custom container image.
 */
class JournaldOtelManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "otel-journald"
        private const val CONFIGMAP_NAME = "otel-journald-config"
        private const val CONFIG_DATA_KEY = "otel-journald-config.yaml"
        private const val IMAGE = "otel/opentelemetry-collector-contrib:latest"
        private const val HEALTH_PORT = 13134
        private const val LIVENESS_INITIAL_DELAY = 10
        private const val LIVENESS_PERIOD = 30
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all journald OTel collector K8s resources in apply order.
     *
     * @return List of: ConfigMap, DaemonSet
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildConfigMap(),
            buildDaemonSet(),
        )

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
                        JournaldOtelManifestBuilder::class.java,
                        "otel-journald-config.yaml",
                    ).substitute(),
            ).build()

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
            ).withArgs("--config=/etc/otel-journald-config.yaml")
            .addNewPort()
            .withContainerPort(HEALTH_PORT)
            .withHostPort(HEALTH_PORT)
            .withProtocol("TCP")
            .withName("health")
            .endPort()
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/otel-journald-config.yaml")
                    .withSubPath(CONFIG_DATA_KEY)
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("host-root")
                    .withMountPath("/host")
                    .withReadOnly(true)
                    .build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(HEALTH_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(HEALTH_PORT)
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
            .withName("host-root")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/")
                    .withType("Directory")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
