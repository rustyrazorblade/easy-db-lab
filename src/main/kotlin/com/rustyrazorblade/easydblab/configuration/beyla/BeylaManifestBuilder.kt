package com.rustyrazorblade.easydblab.configuration.beyla

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
 * Builds all Beyla K8s resources as typed Fabric8 objects.
 *
 * Creates the Beyla eBPF auto-instrumentation DaemonSet and ConfigMap.
 * Beyla provides L7 network RED metrics (Rate, Errors, Duration) for
 * Cassandra and ClickHouse protocols via eBPF.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class BeylaManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "beyla"
        private const val CONFIGMAP_NAME = "beyla-config"
        private const val IMAGE = "grafana/beyla:2.8.1"
    }

    /**
     * Builds all Beyla K8s resources in apply order.
     *
     * @return List of: ConfigMap, DaemonSet
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildConfigMap(),
            buildDaemonSet(),
        )

    /**
     * Builds the Beyla ConfigMap containing beyla-config.yaml.
     */
    fun buildConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                "beyla-config.yaml",
                templateService
                    .fromResource(
                        BeylaManifestBuilder::class.java,
                        "beyla-config.yaml",
                    ).substitute(),
            ).build()

    /**
     * Builds the Beyla DaemonSet.
     *
     * Runs on all nodes with hostNetwork, hostPID, and privileged mode for eBPF access.
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
            .withHostPID(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
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
            .addNewEnv()
            .withName("BEYLA_CONFIG_PATH")
            .withValue("/etc/beyla/beyla-config.yaml")
            .endEnv()
            .withSecurityContext(
                SecurityContextBuilder()
                    .withPrivileged(true)
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
                    .build(),
            ).addNewPort()
            .withContainerPort(Constants.K8s.BEYLA_METRICS_PORT)
            .withHostPort(Constants.K8s.BEYLA_METRICS_PORT)
            .withProtocol("TCP")
            .withName("metrics")
            .endPort()
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/beyla")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("sys-kernel")
                    .withMountPath("/sys/kernel")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("sys-fs-bpf")
                    .withMountPath("/sys/fs/bpf")
                    .build(),
                VolumeMountBuilder()
                    .withName("proc")
                    .withMountPath("/proc")
                    .withReadOnly(true)
                    .build(),
            ).endContainer()
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("sys-kernel")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/sys/kernel")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("sys-fs-bpf")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/sys/fs/bpf")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("proc")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/proc")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
