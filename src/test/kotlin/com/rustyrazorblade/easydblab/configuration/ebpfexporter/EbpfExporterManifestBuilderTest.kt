package com.rustyrazorblade.easydblab.configuration.ebpfexporter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
}
