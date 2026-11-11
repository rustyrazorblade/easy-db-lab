package com.rustyrazorblade.easydblab.configuration.ebpfexporter

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder

/**
 * Builds ebpf_exporter K8s resources as typed Fabric8 objects.
 *
 * Creates a DaemonSet that runs on all nodes with hostNetwork, hostPID,
 * and privileged mode for eBPF access. Uses built-in example programs
 * from the ebpf_exporter container image, except for the programs in
 * [OVERRIDDEN_PROGRAMS]: their compiled objects are built into the AMI by
 * `packer/base/install/install_ebpf_programs.sh` and mounted over the image's
 * copies, so the yaml and `--config.names` stay as shipped.
 *
 * Available built-in programs: https://github.com/cloudflare/ebpf_exporter/tree/master/examples
 */
class EbpfExporterManifestBuilder {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "ebpf-exporter"
        private const val IMAGE = "ghcr.io/cloudflare/ebpf_exporter:v2.5.1"
        private const val EXAMPLES_DIR = "/examples"

        /** Where the base image's install_ebpf_programs.sh puts the compiled objects. */
        internal const val HOST_OBJECT_DIR = "/usr/local/lib/ebpf_exporter"

        /**
         * `--config.names` stems whose compiled object comes from the AMI instead of the image.
         * Each needs `packer/base/install/ebpf/<stem>.bpf.c`.
         */
        internal val OVERRIDDEN_PROGRAMS = listOf("cachestat", "syscalls")

        /** Name of the volume (and its mount) carrying [program]'s object from the AMI. */
        internal fun overrideVolumeName(program: String) = "override-$program"

        /** File name of [program]'s compiled object, in the image and on the AMI alike. */
        internal fun objectFile(program: String) = "$program.bpf.o"
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
                // Every name here must be a file that exists in the image's /examples directory.
                // An unknown one is FATAL, not ignored: the exporter exits with
                // `Error parsing configs: open /examples/<name>.yaml: no such file or directory`,
                // taking the working programs down with it. Verified against v2.5.1 by running the
                // image, which is also how three plausible names were ruled out — `runqlat` and
                // `biosnoop` do not exist in this release at all, and TCP retransmits are
                // `tcp-retransmit`, not `tcpretrans`.
                //
                // `bio-trace` and `sched-trace` are NOT substitutes for the two missing ones: they
                // are span exporters, labelled with trace_id and span_id, so their cardinality is
                // unbounded by construction.
                //
                // `accept-latency` is deliberately absent: on kernel 7.0 it reports 2-64 s accept
                // waits while `ss -ltn` shows an empty accept queue and ListenOverflows stays 0.
                // Its timestamp is keyed by request_sock pointer and only deleted on accept, so a
                // stale entry pairs with a reused address. The tcp-syn-backlog histogram answers
                // the same question and agrees with the kernel.
                //
                // `kfree_skb` is out: the v2.5.1 program keys on destination port and its reason
                // table predates kernel 7.0, so it emits thousands of `unknown:108` series per node.
                // `tcp-window-clamps` and `udp-drops` are out because nothing reads them.
                "--config.names=biolatency,xfsdist,cachestat,shrinklat,tcp-retransmit,oomkill," +
                    "syscalls,softirq-latency,tcp-syn-backlog",
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
            ).addAllToVolumeMounts(
                OVERRIDDEN_PROGRAMS.map { program ->
                    VolumeMountBuilder()
                        .withName(overrideVolumeName(program))
                        .withMountPath("$EXAMPLES_DIR/${objectFile(program)}")
                        .withReadOnly(true)
                        .build()
                },
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
            .addAllToVolumes(
                // Type File: an AMI without the object fails the pod with a mount error that names
                // the path, instead of an exporter that starts and silently loads the image's copy.
                OVERRIDDEN_PROGRAMS.map { program ->
                    VolumeBuilder()
                        .withName(overrideVolumeName(program))
                        .withHostPath(
                            HostPathVolumeSourceBuilder()
                                .withPath("$HOST_OBJECT_DIR/${objectFile(program)}")
                                .withType("File")
                                .build(),
                        ).build()
                },
            ).endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
