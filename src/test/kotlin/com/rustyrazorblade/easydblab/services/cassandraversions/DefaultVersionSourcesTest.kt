package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CassandraBuildManifest
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.CassandraBuildCatalog
import com.rustyrazorblade.easydblab.services.InMemoryObjectStore
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

    private val store = InMemoryObjectStore()

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
            catalog = CassandraBuildCatalog(store, get(), user),
            bucketService = get(),
            user = user,
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

    @Test
    fun `a catalog build presigns its own tarball for the TTL`() {
        val build = manifest("5.1-20260905-a1b2c3d-jdk17")
        CassandraBuildCatalog(store, get(), user).publish(build, File(profileDir, "t.tgz").apply { writeText("x") })

        val url = sources().presignCatalogTarball(build.name, Duration.ofHours(2))

        assertThat(url).isEqualTo("https://mem.invalid/acct-bucket/cassandra-builds/${build.name}/${build.tarball}?expires=7200")
    }

    @Test
    fun `presigning a build the catalog lacks fails, naming the build`() {
        assertThatThrownBy { sources().presignCatalogTarball("no-such-build", Duration.ofHours(1)) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("The build catalog has no build named no-such-build")
    }

    private val user =
        User(
            email = "t@example.com",
            region = "us-west-2",
            keyName = "k",
            awsProfile = "",
            awsAccessKey = "a",
            awsSecret = "s",
        ).apply { s3Bucket = "acct-bucket" }

    private fun manifest(name: String) =
        CassandraBuildManifest(
            name = name,
            baseVersion = "5.1",
            javaVersion = "17",
            gitSha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
            gitShortSha = "a1b2c3d",
            gitBranch = "trunk",
            builtAt = "2026-09-05T12:00:00Z",
            builtBy = "t@example.com",
            tarball = "apache-cassandra-$name-bin.tar.gz",
            tarballBytes = 1L,
            tarballSha256 = "c".repeat(64),
        )

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/services/cassandraversions/$name")).readText()
}
