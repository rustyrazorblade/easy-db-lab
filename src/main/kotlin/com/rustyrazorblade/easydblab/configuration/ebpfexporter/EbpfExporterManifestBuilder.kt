package com.rustyrazorblade.easydblab.configuration.ebpfexporter

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder

/**
 * Builds ebpf_exporter K8s resources as typed Fabric8 objects.
 *
 * Creates a DaemonSet that runs on all nodes with hostNetwork, hostPID,
 * and privileged mode for eBPF access. Uses built-in example programs
 * from the ebpf_exporter container image.
 *
 * Available built-in programs: https://github.com/cloudflare/ebpf_exporter/tree/master/examples
 */
class EbpfExporterManifestBuilder {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "ebpf-exporter"
        private const val IMAGE = "ghcr.io/cloudflare/ebpf_exporter:v2.5.1"
    }

    /**
     * Builds all ebpf_exporter K8s resources in apply order.
     *
     * @return List of: DaemonSet
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildDaemonSet(),
        )

    /**
     * Builds the ebpf_exporter DaemonSet.
     *
     * Runs on all nodes with hostNetwork, hostPID, and privileged mode for eBPF access.
     * Mounts kernel, BPF, proc, modules, and kernel source directories.
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
            .withArgs(
                "--config.dir=/examples",
                "--config.names=biolatency,xfsdist,cachestat",
                "--web.listen-address=0.0.0.0:${Constants.K8s.EBPF_EXPORTER_METRICS_PORT}",
            ).withSecurityContext(
                SecurityContextBuilder()
                    .withPrivileged(true)
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
                    .build(),
            ).addNewPort()
            .withContainerPort(Constants.K8s.EBPF_EXPORTER_METRICS_PORT)
            .withHostPort(Constants.K8s.EBPF_EXPORTER_METRICS_PORT)
            .withProtocol("TCP")
            .withName("metrics")
            .endPort()
            .addToVolumeMounts(
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
                VolumeMountBuilder()
                    .withName("modules")
                    .withMountPath("/lib/modules")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("kernel-src")
                    .withMountPath("/usr/src")
                    .withReadOnly(true)
                    .build(),
            ).endContainer()
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
            .addNewVolume()
            .withName("modules")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/lib/modules")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("kernel-src")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/usr/src")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
