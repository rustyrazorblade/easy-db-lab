package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.server.CassandraVersionInfo
import com.rustyrazorblade.easydblab.server.HostVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CassandraVersionRenderTest {
    @Test
    fun `a uniform cluster shows one version line`() {
        val info = CassandraVersionInfo("5.0.9", "live", hosts = listOf(HostVersion("db0", "5.0.9"), HostVersion("db1", "5.0.9")))

        assertThat(renderCassandraVersion(info)).isEqualTo("\n=== CASSANDRA VERSION ===\nVersion (live): 5.0.9")
    }

    @Test
    fun `a mixed cluster lists each host's version`() {
        val info =
            CassandraVersionInfo(
                "5.0",
                "live",
                hosts = listOf(HostVersion("db0", "5.0"), HostVersion("db1", "4.1.12")),
                mixed = true,
            )

        assertThat(renderCassandraVersion(info))
            .contains("MIXED")
            .contains("db0: 5.0")
            .contains("db1: 4.1.12")
    }

    @Test
    fun `hosts that did not answer are named`() {
        val info = CassandraVersionInfo("5.0.9", "live", hosts = listOf(HostVersion("db1", "5.0.9")), unanswered = listOf("db0"))

        assertThat(renderCassandraVersion(info)).contains("db0")
    }
}
