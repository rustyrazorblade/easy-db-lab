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
 * Builds K8s resources for a Fluent Bit DaemonSet that reads systemd journal entries.
 *
 * Isolated from the main OTel collector so journal collection failures
 * don't affect existing log/metric/trace collection. Forwards logs via OTLP HTTP
 * to the main collector on localhost:4318.
 *
 * Fluent Bit has a native systemd input plugin with journalctl compiled in,
 * so no external binary or chroot is needed.
 */
class JournaldOtelManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "fluent-bit-journald"
        private const val CONFIGMAP_NAME = "fluent-bit-journald-config"
        private const val CONFIG_DATA_KEY = "fluent-bit-journald.yaml"
        private const val IMAGE = "fluent/fluent-bit:latest"
        private const val HTTP_PORT = 2020
        private const val LIVENESS_INITIAL_DELAY = 10
        private const val LIVENESS_PERIOD = 30
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all Fluent Bit journald K8s resources in apply order.
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
                        "fluent-bit-journald.yaml",
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
                    .withRunAsUser(0L)
                    .withNewCapabilities()
                    .addToAdd("DAC_READ_SEARCH")
                    .endCapabilities()
                    .build(),
            ).withArgs(
                "--config=/fluent-bit/etc/config.yaml",
            ).addNewPort()
            .withContainerPort(HTTP_PORT)
            .withHostPort(HTTP_PORT)
            .withProtocol("TCP")
            .withName("http")
            .endPort()
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/fluent-bit/etc/config.yaml")
                    .withSubPath(CONFIG_DATA_KEY)
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("journal")
                    .withMountPath("/var/log/journal")
                    .withReadOnly(true)
                    .build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/api/v1/health")
            .withNewPort(HTTP_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/api/v1/health")
            .withNewPort(HTTP_PORT)
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
            .withName("journal")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/var/log/journal")
                    .withType("Directory")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
