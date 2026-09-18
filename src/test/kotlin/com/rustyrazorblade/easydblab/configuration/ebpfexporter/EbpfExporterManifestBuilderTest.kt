package com.rustyrazorblade.easydblab.configuration.ebpfexporter

import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder.Companion.HOST_OBJECT_DIR
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder.Companion.OVERRIDDEN_PROGRAMS
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder.Companion.objectFile
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder.Companion.overrideVolumeName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the eBPF programs the exporter is told to load.
 *
 * `--config.names` is a list of file stems that must exist in the image's `/examples` directory.
 * An unknown name is fatal, not ignored: the exporter exits during config parsing with
 * `open /examples/<name>.yaml: no such file or directory`, so one bad name takes down every other
 * program with it. That makes this list a set of exact strings verified against the pinned image,
 * not a wish list.
 */
class EbpfExporterManifestBuilderTest {
    private val configNames: List<String> by lazy {
        val builder = EbpfExporterManifestBuilder()
        val args =
            builder
                .buildDaemonSet()
                .spec.template.spec.containers
                .first()
                .args

        args
            .first { it.startsWith("--config.names=") }
            .removePrefix("--config.names=")
            .split(",")
    }

    @Test
    fun `every program that was already loading still loads`() {
        assertThat(configNames).contains("biolatency", "xfsdist", "cachestat")
    }

    @Test
    fun `the added programs use the names the image actually ships`() {
        // Verified by listing /examples in ghcr.io/cloudflare/ebpf_exporter:v2.5.1. Note
        // tcp-retransmit: the upstream BCC tool is called tcpretrans, and that name does not exist
        // here.
        assertThat(configNames).contains("shrinklat", "tcp-retransmit", "oomkill")
    }

    @Test
    fun `the syscall, softirq and network programs are loaded`() {
        // All three ship in v2.5.1's /examples and attach on the 7.0 kernel the base image runs.
        assertThat(configNames).contains("syscalls", "softirq-latency", "tcp-syn-backlog")
    }

    @Test
    fun `the programs ruled out on kernel 7 are not loaded`() {
        // accept-latency: its request_sock-keyed timestamps go stale on kernel 7.0 and it reports
        // multi-second accept waits against an empty accept queue; tcp-syn-backlog covers the same
        // question. kfree_skb: the v2.5.1 program keys on destination port and its reason table
        // predates kernel 7.0, so it emits thousands of `unknown:108` series per node.
        // tcp-window-clamps and udp-drops are read by nothing.
        assertThat(configNames).doesNotContain("accept-latency", "kfree_skb", "tcp-window-clamps", "udp-drops")
    }

    @Test
    fun `no span-exporting program is loaded`() {
        // bio-trace and sched-trace label every series with trace_id and span_id, so their
        // cardinality is unbounded by construction. They are not substitutes for the biosnoop and
        // runqlat programs this release does not ship.
        assertThat(configNames).doesNotContain("bio-trace", "sched-trace", "exec-trace", "sock-trace")
    }

    @Test
    fun `no name carries stray whitespace`() {
        // The list is a comma-joined string; a space after a comma becomes part of the filename the
        // exporter opens, and fails startup exactly like a typo.
        assertThat(configNames).allSatisfy { name ->
            assertThat(name).isEqualTo(name.trim())
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `overridden programs are still named in config names`() {
        // The override replaces the image's object in place; the exporter still finds the program
        // by the same stem.  Dropping the stem would silently disable the override.
        assertThat(configNames).containsAll(OVERRIDDEN_PROGRAMS)
    }

    @Test
    fun `the overridden programs are exactly the sources the AMI build compiles`() {
        // install_ebpf_programs.sh compiles every packer/base/install/ebpf/*.bpf.c. A program listed
        // here without a source there mounts a path the AMI never creates; a source there without
        // an entry here is built into the AMI and then never mounted, so the image's copy runs.
        val sources =
            File("packer/base/install/ebpf")
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.endsWith(".bpf.c") }
                .map { it.removeSuffix(".bpf.c") }
                .toSet()

        assertThat(OVERRIDDEN_PROGRAMS.toSet()).isEqualTo(sources)
    }

    @Test
    fun `each overridden object is mounted from the AMI over the image copy`() {
        val daemonSet = EbpfExporterManifestBuilder().buildDaemonSet()
        val spec = daemonSet.spec.template.spec
        val mounts = spec.containers.first().volumeMounts

        assertThat(OVERRIDDEN_PROGRAMS).isNotEmpty()
        OVERRIDDEN_PROGRAMS.forEach { program ->
            val mount = mounts.first { it.name == overrideVolumeName(program) }
            val volume = spec.volumes.first { it.name == overrideVolumeName(program) }
            assertThat(mount.mountPath).isEqualTo("/examples/${objectFile(program)}")
            assertThat(volume.hostPath.path).isEqualTo("$HOST_OBJECT_DIR/${objectFile(program)}")
            // A missing object must fail the pod visibly, not fall back to the image's copy.
            assertThat(volume.hostPath.type).isEqualTo("File")
        }
    }
}
