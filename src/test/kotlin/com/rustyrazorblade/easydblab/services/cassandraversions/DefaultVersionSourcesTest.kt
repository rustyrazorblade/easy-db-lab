package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.component.get
import org.mockito.kotlin.mock
import java.io.File
import java.time.Duration

/**
 * The real sources over a fake network: an OkHttp interceptor answers each URL and counts the
 * calls. It proves the wiring the cache tests cannot: which URL each list reads, that a 404 is a
 * failed fetch (stale with a file, an error without one), and that a cached list costs no request.
 */
class DefaultVersionSourcesTest : BaseKoinTest() {
    @TempDir
    lateinit var profileDir: File

    private val asfIndex = fixture("asf-archive-index.html")
    private val nightly = fixture("cassandra-builds-nightly.json")

    /** The body each URL answers; a URL not in the map answers 404. */
    private val answers = mutableMapOf<String, String>()
    private val requests = mutableListOf<String>()

    /** URLs that answer 503 on every attempt. */
    private val unavailable = mutableSetOf<String>()

    private fun sources(): DefaultVersionSources {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        val url = chain.request().url.toString()
                        requests += url
                        val body = answers[url]
                        val code =
                            when {
                                url in unavailable -> SERVICE_UNAVAILABLE
                                body == null -> Constants.HttpStatus.NOT_FOUND
                                else -> Constants.HttpStatus.OK
                            }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message("")
                            .body((body ?: "").toResponseBody("text/plain".toMediaType()))
                            .build()
                    },
                ).build()
        return DefaultVersionSources(
            profileDir = profileDir,
            profileBuildDir = File(profileDir, "cassandra_versions"),
            catalog = mock(),
            bucketService = get(),
            user = get(),
            http = PublicHttp(client, PublicHttp.retryConfig(Duration.ZERO)),
        )
    }

    @Test
    fun `the ASF list is read from the archive index once, then from the cache`() {
        answers[AsfReleases.indexUrl()] = asfIndex

        val first = sources().asfReleases(refresh = false)
        val second = sources().asfReleases(refresh = false)

        assertThat(first.value.resolve("5.0")).isEqualTo("5.0.9")
        assertThat(second.value).isEqualTo(first.value)
        assertThat(second.stale).isFalse()
        assertThat(requests).containsExactly(AsfReleases.indexUrl())
    }

    @Test
    fun `a 404 on refresh with a cached list returns the cached list marked stale`() {
        answers[Constants.Cassandra.CASSANDRA_BUILDS_RELEASE_API] = nightly
        val cached = sources().cassandraBuilds(refresh = false)
        answers.clear()

        val refreshed = sources().cassandraBuilds(refresh = true)

        assertThat(refreshed.stale).isTrue()
        assertThat(refreshed.value).isEqualTo(cached.value).isNotEmpty()
        assertThat(requests).containsExactly(
            Constants.Cassandra.CASSANDRA_BUILDS_RELEASE_API,
            Constants.Cassandra.CASSANDRA_BUILDS_RELEASE_API,
        )
    }

    @Test
    fun `a 404 with no cached list fails and names the source and the URL`() {
        assertThatThrownBy { sources().asfReleases(refresh = false) }
            .isInstanceOf(VersionSourceUnavailableException::class.java)
            .hasMessageContaining("Apache release")
            .hasMessageContaining(AsfReleases.indexUrl())
    }

    @Test
    fun `a build with no manifest asset reads no manifest`() {
        val build = CassandraBuildsRelease.parse(nightly).first().copy(manifestUrl = null)

        assertThat(sources().cassandraBuildsManifest(build)).isNull()
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a manifest that stays unavailable is read as no manifest, so the release-line table decides`() {
        val build = CassandraBuildsRelease.parse(nightly).first().copy(manifestUrl = "https://example.test/manifest.json")
        unavailable += "https://example.test/manifest.json"

        assertThat(sources().cassandraBuildsManifest(build)).isNull()
        assertThat(requests).contains("https://example.test/manifest.json")
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/services/cassandraversions/$name")).readText()
}
