package com.rustyrazorblade.easydblab.services.cassandraversions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The Apache release list, parsed from a captured archive index page (2026-11-16). */
class AsfReleasesTest {
    private val index =
        checkNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/services/cassandraversions/asf-archive-index.html")).readText()
    private val releases = AsfReleases.parseIndex(index)

    @ParameterizedTest(name = "{0} resolves to {1}")
    @CsvSource(
        "5.0, 5.0.9",
        "4.1, 4.1.12",
        "4.0, 4.0.21",
        "3.11, 3.11.19",
        "5.0.6, 5.0.6",
        "4.1.7, 4.1.7",
        "5.0-rc1, 5.0-rc1",
        "3.10, 3.10",
    )
    fun `a line resolves to its highest GA patch and an exact name to itself`(
        name: String,
        release: String,
    ) {
        assertThat(releases.resolve(name)).isEqualTo(release)
    }

    @ParameterizedTest(name = "{0} is no release")
    @CsvSource("6.0", "5.0.99", "5.0-HEAD", "trunk", "cassandra-sidecar", "KEYS")
    fun `a name the archive does not hold resolves to nothing`(name: String) {
        assertThat(releases.resolve(name)).isNull()
    }

    @Test
    fun `a pre-release is never picked for a line, even when it is the newest name`() {
        val list = AsfReleases(listOf("6.0-alpha1", "6.0-beta1", "5.0-rc1", "5.0.0", "5.0.1"))

        assertThat(list.resolve("6.0")).isNull()
        assertThat(list.resolve("5.0")).isEqualTo("5.0.1")
    }

    @Test
    fun `patches order by number, not by text`() {
        val list = AsfReleases(listOf("4.1.9", "4.1.10", "4.1.2"))

        assertThat(list.resolve("4.1")).isEqualTo("4.1.10")
        assertThat(list.lines().single().patches).containsExactly("4.1.2", "4.1.9", "4.1.10")
    }

    @Test
    fun `the lines list the latest release and every patch, newest line first`() {
        val lines = releases.lines()

        assertThat(lines.first().line).isEqualTo("5.0")
        assertThat(lines.first().latest).isEqualTo("5.0.9")
        assertThat(lines.first().patches).startsWith("5.0.0", "5.0.1").endsWith("5.0.9").doesNotContain("5.0-rc1")
        assertThat(lines.map { it.line }).contains("4.1", "4.0", "3.11", "3.0").doesNotContain("6.0")
    }

    @Test
    fun `only release directories are read from the page`() {
        assertThat(releases.names).contains("0.7.6-2", "4.0-alpha1", "5.0.9").noneMatch { it.contains("/") || it.startsWith("cassandra") }
    }

    @Test
    fun `download URLs are the CDN first and the archive second`() {
        assertThat(AsfReleases.downloadUrl("5.0.6"))
            .isEqualTo("https://dlcdn.apache.org/cassandra/5.0.6/apache-cassandra-5.0.6-bin.tar.gz")
        assertThat(AsfReleases.archiveUrl("5.0.6"))
            .isEqualTo("https://archive.apache.org/dist/cassandra/5.0.6/apache-cassandra-5.0.6-bin.tar.gz")
    }
}
