package com.rustyrazorblade.easydblab.services.cassandraversions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The cassandra-builds `nightly` release, parsed from captured GitHub API JSON (2026-11-16). */
class CassandraBuildsReleaseTest {
    private val json =
        checkNotNull(
            javaClass.getResource("/com/rustyrazorblade/easydblab/services/cassandraversions/cassandra-builds-nightly.json"),
        ).readText()

    @Test
    fun `each tarball asset is a build named by its ref, and the release has no manifest yet`() {
        val builds = CassandraBuildsRelease.parse(json)

        assertThat(builds.map { it.ref })
            .containsExactlyInAnyOrder("5.0-HEAD", "5.0-rustyrazorblade-HEAD", "6.0-HEAD", "6.0-rustyrazorblade-HEAD", "trunk")
        assertThat(builds.single { it.ref == "trunk" }.url)
            .isEqualTo("https://github.com/rustyrazorblade/cassandra-builds/releases/download/nightly/apache-cassandra-trunk-bin.tar.gz")
        assertThat(builds).allMatch { it.manifestUrl == null }
    }

    @Test
    fun `each build carries its tarball asset's GitHub digest`() {
        val trunk = CassandraBuildsRelease.parse(json).single { it.ref == "trunk" }

        assertThat(trunk.digest).matches("sha256:[0-9a-f]{64}")
        assertThat(TarballDigests.github(trunk.digest, trunk.url).hex).isEqualTo(trunk.digest?.removePrefix("sha256:"))
    }

    @Test
    fun `an asset with no digest gives a build with none, which resolution refuses`() {
        val noDigest =
            """
            {"assets": [{"name": "apache-cassandra-trunk-bin.tar.gz", "browser_download_url": "https://h/t.tgz", "digest": null}]}
            """.trimIndent()

        assertThat(CassandraBuildsRelease.parse(noDigest).single().digest).isNull()
    }

    @Test
    fun `a manifest asset beside a tarball is linked to it, and a stray asset is ignored`() {
        val withManifest =
            """
            {"tag_name": "nightly", "assets": [
              {"name": "apache-cassandra-5.0-HEAD-bin.tar.gz", "browser_download_url": "https://h/apache-cassandra-5.0-HEAD-bin.tar.gz"},
              {"name": "apache-cassandra-5.0-HEAD-bin.json", "browser_download_url": "https://h/apache-cassandra-5.0-HEAD-bin.json"},
              {"name": "apache-cassandra-trunk-bin.json", "browser_download_url": "https://h/apache-cassandra-trunk-bin.json"},
              {"name": "checksums.txt", "browser_download_url": "https://h/checksums.txt"}
            ]}
            """.trimIndent()

        val build = CassandraBuildsRelease.parse(withManifest).single()

        assertThat(build.ref).isEqualTo("5.0-HEAD")
        assertThat(build.manifestUrl).isEqualTo("https://h/apache-cassandra-5.0-HEAD-bin.json")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"ref": "5.0-HEAD", "sha": "abc", "java_default": "11", "java_supported": ["11", "17"], "ant_flags": ""}""",
            """{"ref": "5.0-HEAD", "java_default": 11, "java_supported": "11,17", "new_field": 1}""",
            """{"ref": "5.0-HEAD", "java_default": "11", "java_supported": [11, 17]}""",
        ],
    )
    fun `a manifest gives the JDK list in either form`(manifest: String) {
        val parsed = CassandraBuildsRelease.parseManifest(manifest)

        assertThat(parsed.javaSupported).containsExactly("11", "17")
        assertThat(parsed.javaDefault).isEqualTo("11")
    }

    @Test
    fun `a manifest with no JDK list gives an empty list`() {
        assertThat(CassandraBuildsRelease.parseManifest("""{"ref": "trunk"}""").javaSupported).isEmpty()
    }

    @Test
    fun `JSON that is not a release fails, naming what is missing`() {
        assertThatThrownBy { CassandraBuildsRelease.parse("""{"message": "Not Found"}""") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("assets")
    }
}
