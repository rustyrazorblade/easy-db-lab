package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.configuration.CassandraSource
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.configuration.InstalledVersion
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.S3Uri
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant

/**
 * The cassandra-pods kit's artifact resolution: `--version` through the same resolver `cassandra use`
 * uses, `--build` through cassandra-builds alone, and the sha256 from each source.
 */
class CassandraTarballResolverTest {
    private val asfSha = "a".repeat(64)
    private val nightlySha = "b".repeat(64)
    private val catalogSha = "c".repeat(64)

    private class Sources(
        val asf: List<String> = listOf("5.0.5", "5.0.6"),
        val nightly: List<CassandraBuild> = emptyList(),
        val catalog: Map<String, CatalogBuild> = emptyMap(),
        val manifests: Map<String, CassandraBuildsManifest> = emptyMap(),
        val profiles: Map<String, ProfileBuild> = emptyMap(),
    ) : VersionSources {
        val calls = mutableListOf<String>()
        val presigned = mutableListOf<Pair<String, Duration>>()

        override val profileBuildDir = File("/profile/cassandra_versions")

        override fun profileBuild(name: String): ProfileBuild? = profiles[name]

        override fun profileBuilds(): List<ProfileBuild> = profiles.values.toList()

        override fun asfReleases(refresh: Boolean): Cached<AsfReleases> {
            calls += "asf"
            return Cached(AsfReleases(asf), Instant.EPOCH, stale = false)
        }

        override fun cassandraBuilds(refresh: Boolean): Cached<List<CassandraBuild>> {
            calls += "cassandra-builds"
            return Cached(nightly, Instant.EPOCH, stale = false)
        }

        override fun cassandraBuildsManifest(build: CassandraBuild): CassandraBuildsManifest? = manifests[build.ref]

        override fun catalogBuild(name: String): CatalogBuild? = catalog[name].also { calls += "catalog" }

        override fun catalogBuildNames(): List<String> = catalog.keys.toList()

        override fun presignCatalogTarball(
            name: String,
            ttl: Duration,
        ): String {
            presigned += name to ttl
            return "https://signed.example/$name.tgz?X-Amz-Expires=${ttl.seconds}"
        }

        override fun downloadCacheRoot(): ClusterStoragePath = ClusterStoragePath.downloadCacheRoot("acct-bucket")

        override fun uriOf(path: ClusterStoragePath): String = S3Uri.uri(path)
    }

    private inner class Http : ChecksumHttp {
        override fun getOrNull(url: String): String? = asfSha.takeIf { url == "${AsfReleases.downloadUrl("5.0.6")}.sha256" }

        override fun <T> streamOrNull(
            url: String,
            read: (InputStream) -> T,
        ): T? = null
    }

    private val state = ClusterState(name = "c")

    private fun resolver(
        sources: Sources,
        releaseLines: ReleaseLines = ReleaseLines.load(),
    ) = CassandraTarballResolver(
        CassandraVersionResolver(sources),
        sources,
        TarballDigests(Http(), EventBus()),
        Duration.ofHours(3),
        releaseLines,
    )

    private fun nightly(ref: String) = CassandraBuild(ref, "https://gh/$ref.tgz", digest = "sha256:$nightlySha")

    @Test
    fun `--version of an ASF line runs its newest release's CDN tarball, checked against the published sha256`() {
        val artifact = resolver(Sources()).resolveVersion("5.0", java = null, state)

        assertThat(artifact.url).isEqualTo(AsfReleases.downloadUrl("5.0.6"))
        assertThat(artifact.fallbackUrl).isEqualTo(AsfReleases.archiveUrl("5.0.6"))
        assertThat(artifact.sha256).isEqualTo(Sha256(asfSha))
        assertThat(artifact.build).isEqualTo("5.0.6")
        assertThat(artifact.java).isEqualTo("17")
    }

    @Test
    fun `--java picks the JDK, and one the line does not run on is refused`() {
        assertThat(resolver(Sources()).resolveVersion("5.0", java = "11", state).java).isEqualTo("11")
        assertThatThrownBy { resolver(Sources()).resolveVersion("5.0", java = "8", state) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("JDK 8")
    }

    @Test
    fun `--version of a cassandra-builds ref is checked against its GitHub digest`() {
        val sources = Sources(nightly = listOf(CassandraBuild("5.0-HEAD", "https://gh/5.0-HEAD.tgz", digest = "sha256:$nightlySha")))

        val artifact = resolver(sources).resolveVersion("5.0-HEAD", java = null, state)

        assertThat(artifact.url).isEqualTo("https://gh/5.0-HEAD.tgz")
        assertThat(artifact.fallbackUrl).isNull()
        assertThat(artifact.sha256).isEqualTo(Sha256(nightlySha))
        assertThat(artifact.build).isEqualTo("5.0-HEAD")
    }

    @Test
    fun `--version of a catalog build gets a presigned URL for the TTL and the manifest's sha256`() {
        val name = "5.1-20260905-a1b2c3d-jdk17"
        val sources = Sources(catalog = mapOf(name to CatalogBuild("s3://b/x.tgz", listOf("17", "21"), "17", catalogSha)))

        val artifact = resolver(sources).resolveVersion(name, java = null, state)

        assertThat(artifact.url).isEqualTo("https://signed.example/$name.tgz?X-Amz-Expires=10800")
        assertThat(sources.presigned).containsExactly(name to Duration.ofHours(3))
        assertThat(artifact.sha256).isEqualTo(Sha256(catalogSha))
        assertThat(artifact.build).isEqualTo(name)
        assertThat(artifact.java).isEqualTo("17")
    }

    @Test
    fun `a catalog build whose manifest has no sha256 fails, naming the build`() {
        val sources = Sources(catalog = mapOf("b1" to CatalogBuild("s3://b/b1.tgz", listOf("17"))))

        assertThatThrownBy { resolver(sources).resolveVersion("b1", java = null, state) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("b1")
        assertThat(sources.presigned).isEmpty()
    }

    @Test
    fun `a VM node's ASF record of the name still resolves to the ASF tarball`() {
        val record =
            InstalledVersion(
                version = "5.0",
                java = "17",
                javaSupported = listOf("11", "17"),
                python = "3.11.9",
                source = CassandraSource.ASF,
                url = AsfReleases.downloadUrl("5.0.6"),
            )
        val withRecord = ClusterState(name = "c", nodes = mutableMapOf("db0" to NodeState(installed = mutableMapOf("5.0" to record))))

        assertThat(resolver(Sources()).resolveVersion("5.0", java = null, withRecord).sha256).isEqualTo(Sha256(asfSha))
    }

    @Test
    fun `an unknown --version fails, naming it`() {
        assertThatThrownBy { resolver(Sources()).resolveVersion("9.9.9", java = null, state) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("9.9.9")
    }

    @Test
    fun `--build reads cassandra-builds alone and runs the build's tarball`() {
        val sources = Sources(nightly = listOf(CassandraBuild("5.0-HEAD", "https://gh/5.0-HEAD.tgz", digest = "sha256:$nightlySha")))

        val artifact = resolver(sources).resolveBuild("5.0-HEAD", java = null)

        assertThat(artifact.url).isEqualTo("https://gh/5.0-HEAD.tgz")
        assertThat(artifact.sha256).isEqualTo(Sha256(nightlySha))
        assertThat(sources.calls).containsOnly("cassandra-builds")
    }

    @Test
    fun `--build of a name cassandra-builds lacks fails naming cassandra-builds and the ref, never reading ASF`() {
        val sources = Sources()

        assertThatThrownBy { resolver(sources).resolveBuild("5.0", java = null) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("cassandra-builds")
            .hasMessageContaining("5.0")
        assertThat(sources.calls).doesNotContain("asf", "catalog")
    }

    @Test
    fun `--build with no --java runs the highest JDK the build's manifest lists`() {
        val sources =
            Sources(
                nightly = listOf(nightly("feature-x")),
                manifests = mapOf("feature-x" to CassandraBuildsManifest(listOf("11", "17"), "11")),
            )

        assertThat(resolver(sources).resolveBuild("feature-x", java = null).java).isEqualTo("17")
    }

    @Test
    fun `--build with no manifest takes its JDKs from the release-line table`() {
        val lines = ReleaseLines(listOf(ReleaseLine("5.0", listOf("11", "17"))))

        assertThat(resolver(Sources(nightly = listOf(nightly("5.0-HEAD"))), lines).resolveBuild("5.0-HEAD", java = null).java)
            .isEqualTo("17")
    }

    @Test
    fun `--build with a --java the build does not run on is refused, naming the JDK and the supported list`() {
        val sources =
            Sources(
                nightly = listOf(nightly("feature-x")),
                manifests = mapOf("feature-x" to CassandraBuildsManifest(listOf("11", "17"), "11")),
            )

        assertThatThrownBy { resolver(sources).resolveBuild("feature-x", java = "8") }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("JDK 8")
            .hasMessageContaining("11, 17")
    }

    @Test
    fun `--build with no source of JDKs and no --java fails, asking for --java`() {
        val sources = Sources(nightly = listOf(nightly("feature-x")))

        assertThatThrownBy { resolver(sources, ReleaseLines(emptyList())).resolveBuild("feature-x", java = null) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("feature-x")
            .hasMessageContaining("Pass --java")
    }

    @Test
    fun `--version naming a profile build is refused, naming the source and its URL`() {
        val profile = ProfileBuild(version = "mybuild", url = "https://example.test/mybuild.tgz", javaSupported = listOf("17"), java = "17")

        assertThatThrownBy { resolver(Sources(profiles = mapOf("mybuild" to profile))).resolveVersion("mybuild", java = null, state) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("PROFILE")
            .hasMessageContaining("https://example.test/mybuild.tgz")
            .hasMessageContaining("--image")
    }

    @Test
    fun `a cassandra-builds build with no digest fails, naming its tarball URL`() {
        val sources = Sources(nightly = listOf(CassandraBuild("trunk", "https://gh/trunk.tgz")))

        assertThatThrownBy { resolver(sources).resolveBuild("trunk", java = null) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("https://gh/trunk.tgz")
    }
}
