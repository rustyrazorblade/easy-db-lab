package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CassandraBuildManifestTest {
    private val date = LocalDate.of(2026, 9, 5)

    @Test
    fun `build name carries version, ticket, date, sha and jdk`() {
        val name = CassandraBuildManifest.buildName("5.1", "CASSANDRA-19000", date, "a1b2c3d", "17")

        assertThat(name).isEqualTo("5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17")
    }

    @Test
    fun `build name drops the ticket segment entirely when there is no ticket`() {
        val name = CassandraBuildManifest.buildName("5.0", null, date, "9876abc", "17")

        assertThat(name).isEqualTo("5.0-20260905-9876abc-jdk17")
    }

    @Test
    fun `a blank ticket is treated as no ticket rather than an empty segment`() {
        val name = CassandraBuildManifest.buildName("5.0", "   ", date, "9876abc", "17")

        assertThat(name).isEqualTo("5.0-20260905-9876abc-jdk17")
    }

    @Test
    fun `a lowercase ticket is normalised so one ticket yields one prefix`() {
        val name = CassandraBuildManifest.buildName("trunk", "cassandra-20111", date, "1122def", "21")

        assertThat(name).isEqualTo("trunk-CASSANDRA-20111-20260905-1122def-jdk21")
    }

    @Test
    fun `a SNAPSHOT suffix never reaches the name`() {
        val name = CassandraBuildManifest.buildName("5.1-SNAPSHOT", null, date, "a1b2c3d", "17")

        assertThat(name).startsWith("5.1-2026")
    }

    @Test
    fun `a label sits between the ticket and the date`() {
        val name = CassandraBuildManifest.buildName("5.1", "CASSANDRA-19000", date, "a1b2c3d", "17", "flushfix")

        assertThat(name).isEqualTo("5.1-CASSANDRA-19000-flushfix-20260905-a1b2c3d-jdk17")
    }

    @Test
    fun `a label without a ticket still follows the version`() {
        val name = CassandraBuildManifest.buildName("5.1", null, date, "a1b2c3d", "17", "flushfix")

        assertThat(name).isEqualTo("5.1-flushfix-20260905-a1b2c3d-jdk17")
    }

    @Test
    fun `every name keeps the version first so a flat listing sorts by release`() {
        val labelled = CassandraBuildManifest.buildName("5.1", "CASSANDRA-19000", date, "a1b2c3d", "17", "flushfix")
        val unlabelled = CassandraBuildManifest.buildName("5.1", null, date, "a1b2c3d", "17")

        assertThat(labelled).startsWith("5.1-")
        assertThat(unlabelled).startsWith("5.1-")
    }

    @Test
    fun `an absent or blank label adds no segment`() {
        assertThat(CassandraBuildManifest.buildName("5.1", null, date, "a1b2c3d", "17", null))
            .isEqualTo("5.1-20260905-a1b2c3d-jdk17")
        assertThat(CassandraBuildManifest.buildName("5.1", null, date, "a1b2c3d", "17", "   "))
            .isEqualTo("5.1-20260905-a1b2c3d-jdk17")
    }

    @Test
    fun `a usable label is accepted and trimmed`() {
        assertThat(CassandraBuildManifest.validateLabel("  flush-fix_2.1  ")).isEqualTo("flush-fix_2.1")
    }

    @Test
    fun `a label with a space is refused rather than silently rewritten`() {
        // The name is the build's identity in S3 and on every node, so a quietly mangled label
        // would produce a build the operator did not ask for.
        assertThatThrownBy { CassandraBuildManifest.validateLabel("flush fix") }
            .hasMessageContaining("' '")
    }

    @Test
    fun `a label naming characters that would break an S3 key is refused, listing them`() {
        assertThatThrownBy { CassandraBuildManifest.validateLabel("flush/fix?v=1") }
            .hasMessageContaining("'/'")
            .hasMessageContaining("'?'")
            .hasMessageContaining("'='")
    }

    @Test
    fun `a blank label is refused`() {
        assertThatThrownBy { CassandraBuildManifest.validateLabel("   ") }
            .hasMessageContaining("cannot be blank")
    }

    @Test
    fun `an over-long label is refused with its length`() {
        assertThatThrownBy { CassandraBuildManifest.validateLabel("x".repeat(41)) }
            .hasMessageContaining("41")
    }

    @Test
    fun `tarball name is derived from the build name so a downloaded file identifies itself`() {
        assertThat(CassandraBuildManifest.tarballName("5.1-20260905-a1b2c3d-jdk17"))
            .isEqualTo("apache-cassandra-5.1-20260905-a1b2c3d-jdk17-bin.tar.gz")
    }

    @Test
    fun `a manifest survives a round trip through S3`() {
        val manifest = sampleManifest()

        val decoded = CassandraBuildManifest.parse(manifest.encode())

        assertThat(decoded).isEqualTo(manifest)
    }

    @Test
    fun `a manifest written by a newer version still parses`() {
        val withUnknownField =
            sampleManifest().encode().replaceFirst("{", """{ "somethingAddedLater": "value",""")

        val decoded = CassandraBuildManifest.parse(withUnknownField)

        assertThat(decoded.name).isEqualTo("5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17")
    }

    private fun sampleManifest() =
        CassandraBuildManifest(
            name = "5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17",
            baseVersion = "5.1",
            javaVersion = "17",
            gitSha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
            gitShortSha = "a1b2c3d",
            gitBranch = "CASSANDRA-19000-trunk",
            gitRemote = "https://github.com/apache/cassandra.git",
            dirty = true,
            jira = "CASSANDRA-19000",
            antFlags = "-Duse.jdk11=true",
            builtAt = "2026-09-05T12:00:00Z",
            builtBy = "jon@rustyrazorblade.com",
            tarball = "apache-cassandra-5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17-bin.tar.gz",
            tarballBytes = 78_123_456L,
            tarballSha256 = "abc123",
        )
}
