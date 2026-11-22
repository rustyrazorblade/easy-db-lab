package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where each tarball's sha256 comes from (design: TarballDigests). The pod's fetch checks the
 * download against it and caches the tree under it, so a wrong or missing digest must fail at
 * `start`, before any resource exists.
 */
class TarballDigestsTest {
    private val tarball = "not really a tarball".toByteArray()
    private val sha256 = hex("SHA-256", tarball)
    private val sha512 = hex("SHA-512", tarball)

    private val release = "5.0.6"
    private val cdn = AsfReleases.downloadUrl(release)
    private val archive = AsfReleases.archiveUrl(release)

    private val events = mutableListOf<Event>()
    private val bus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    /** Text and bodies by URL; every other URL answers 404. Records every streamed download. */
    private class FakeHttp(
        val texts: Map<String, String> = emptyMap(),
        val bodies: Map<String, ByteArray> = emptyMap(),
    ) : ChecksumHttp {
        val streamed = mutableListOf<String>()

        override fun getOrNull(url: String): String? = texts[url]

        override fun <T> streamOrNull(
            url: String,
            read: (InputStream) -> T,
        ): T? {
            streamed += url
            return bodies[url]?.let { read(ByteArrayInputStream(it)) }
        }
    }

    @Test
    fun `Sha256 accepts 64 lowercase hex characters`() {
        assertThat(Sha256(sha256).hex).isEqualTo(sha256)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "ZZ", "4117C67E717E4190957DD11AA097FA9984E97E8EEDD409F153A80F21F2C2940A"])
    fun `Sha256 rejects anything but 64 lowercase hex characters`(value: String) {
        assertThatThrownBy { Sha256(value) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sha256")
    }

    @Test
    fun `Sha256 parse lowercases and trims, and gives null for a value that is not a sha256`() {
        assertThat(Sha256.parse("  ${sha256.uppercase()}\n")).isEqualTo(Sha256(sha256))
        assertThat(Sha256.parse("deadbeef")).isNull()
        assertThat(Sha256.parse(null)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["%s", "%s  apache-cassandra-5.0.6-bin.tar.gz", "%s *apache-cassandra-5.0.6-bin.tar.gz\n"])
    fun `an ASF sha256 file in either form gives its hash without downloading the tarball`(form: String) {
        val http = FakeHttp(texts = mapOf("$cdn.sha256" to form.format(sha256)))

        assertThat(TarballDigests(http, bus).asf(release)).isEqualTo(Sha256(sha256))
        assertThat(http.streamed).isEmpty()
    }

    @Test
    fun `the archive's sha256 is used when the CDN has none`() {
        val http = FakeHttp(texts = mapOf("$archive.sha256" to sha256))

        assertThat(TarballDigests(http, bus).asf(release)).isEqualTo(Sha256(sha256))
    }

    @Test
    fun `with only a sha512 published, one download is checked against it and its sha256 is computed`() {
        val http = FakeHttp(texts = mapOf("$cdn.sha512" to "$sha512  apache-cassandra-5.0.6-bin.tar.gz"), bodies = mapOf(cdn to tarball))

        assertThat(TarballDigests(http, bus).asf(release)).isEqualTo(Sha256(sha256))
        assertThat(http.streamed).containsExactly(cdn)
    }

    /** Streaming a whole tarball takes a while; the operator is told before it starts, and which URL. */
    @Test
    fun `computing a sha256 from the download says it is downloading, naming the URL, before it streams`() {
        val http = FakeHttp(texts = mapOf("$cdn.sha512" to sha512), bodies = mapOf(cdn to tarball))

        TarballDigests(http, bus).asf(release)

        assertThat(events.filterIsInstance<Event.Cassandra.TarballDigestDownloading>().single().url).isEqualTo(cdn)
    }

    @Test
    fun `a published sha256 downloads nothing and says nothing`() {
        TarballDigests(FakeHttp(texts = mapOf("$cdn.sha256" to sha256)), bus).asf(release)

        assertThat(events).isEmpty()
    }

    @Test
    fun `an uppercase published sha512 matches the download`() {
        val http = FakeHttp(texts = mapOf("$cdn.sha512" to sha512.uppercase()), bodies = mapOf(cdn to tarball))

        assertThat(TarballDigests(http, bus).asf(release)).isEqualTo(Sha256(sha256))
    }

    /** The sha512 named the CDN's tarball; trying the archive's instead would check a file against another's hash. */
    @Test
    fun `with only a sha512 published and the tarball 404, it fails without falling through to the archive`() {
        val http =
            FakeHttp(
                texts = mapOf("$cdn.sha512" to sha512, "$archive.sha512" to sha512),
                bodies = mapOf(archive to tarball),
            )

        assertThatThrownBy { TarballDigests(http, bus).asf(release) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("could not be downloaded to compute its sha256")
            .hasMessageContaining(cdn)
        assertThat(http.streamed).containsExactly(cdn)
    }

    @Test
    fun `a download that does not match the published sha512 fails, naming both hashes`() {
        val wrong = "0".repeat(128)
        val http = FakeHttp(texts = mapOf("$cdn.sha512" to wrong), bodies = mapOf(cdn to tarball))

        assertThatThrownBy { TarballDigests(http, bus).asf(release) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining(wrong)
            .hasMessageContaining(sha512)
            .hasMessageContaining(cdn)
    }

    @Test
    fun `an ASF release with no sha256 and no sha512 fails, naming the tarball URL`() {
        assertThatThrownBy { TarballDigests(FakeHttp(), bus).asf(release) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining(cdn)
    }

    @Test
    fun `a malformed sha256 file is not a digest`() {
        val http = FakeHttp(texts = mapOf("$cdn.sha256" to "<html>Not Found</html>"))

        assertThatThrownBy { TarballDigests(http, bus).asf(release) }.isInstanceOf(VersionResolutionException::class.java)
    }

    @Test
    fun `a GitHub asset digest gives its sha256`() {
        assertThat(TarballDigests.github("sha256:$sha256", "https://gh/t.tgz")).isEqualTo(Sha256(sha256))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "sha256:", "sha256:abc", "sha512:0000"])
    fun `a missing or malformed GitHub digest fails, naming the tarball URL`(digest: String) {
        assertThatThrownBy { TarballDigests.github(digest.ifEmpty { null }, "https://gh/t.tgz") }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("https://gh/t.tgz")
    }

    @Test
    fun `a catalog manifest's tarballSha256 is its digest, and a blank one fails naming the build`() {
        assertThat(TarballDigests.catalog(sha256, "my-build")).isEqualTo(Sha256(sha256))
        assertThatThrownBy { TarballDigests.catalog("", "my-build") }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("my-build")
    }

    private fun hex(
        algorithm: String,
        bytes: ByteArray,
    ) = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
}
