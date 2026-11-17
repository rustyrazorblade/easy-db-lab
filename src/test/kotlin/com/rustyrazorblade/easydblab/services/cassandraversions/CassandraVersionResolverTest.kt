package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CassandraSource
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InstalledVersion
import com.rustyrazorblade.easydblab.configuration.NodeState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.time.Instant

/**
 * The resolution rules of design D1 (source order), D2 (JDK and Python) and D13 (profile builds),
 * against a fake source set that counts the calls each source gets.
 */
class CassandraVersionResolverTest {
    /** Fixed source lists, with a call count per source so a test proves a source was not read. */
    private class FakeSources(
        val profile: List<ProfileBuild> = emptyList(),
        var asf: List<String> = listOf("4.1.6", "4.1.7", "5.0.5", "5.0.6", "5.0-rc1"),
        var asfOnRefresh: List<String>? = null,
        val asfRefreshFails: Boolean = false,
        val nightly: List<CassandraBuild> =
            listOf(CassandraBuild("5.0-HEAD", "https://gh/5.0-HEAD.tgz"), CassandraBuild("trunk", "https://gh/trunk.tgz")),
        val manifests: Map<String, CassandraBuildsManifest> = emptyMap(),
        val catalog: Map<String, CatalogBuild> = emptyMap(),
    ) : VersionSources {
        val calls = mutableListOf<String>()

        override val profileBuildDir = File("/profile/cassandra_versions")

        override fun profileBuild(name: String): ProfileBuild? = profile.firstOrNull { it.version == name }.also { calls += "profile" }

        override fun profileBuilds(): List<ProfileBuild> = profile

        override fun asfReleases(refresh: Boolean): Cached<AsfReleases> {
            calls += if (refresh) "asf-refresh" else "asf"
            if (refresh) asfOnRefresh?.let { asf = it }
            return Cached(AsfReleases(asf), Instant.EPOCH, stale = refresh && asfRefreshFails)
        }

        override fun cassandraBuilds(refresh: Boolean): Cached<List<CassandraBuild>> {
            calls += "cassandra-builds"
            return Cached(nightly, Instant.EPOCH, stale = false)
        }

        override fun cassandraBuildsManifest(build: CassandraBuild): CassandraBuildsManifest? = manifests[build.ref]

        override fun catalogBuild(name: String): CatalogBuild? = catalog[name].also { calls += "catalog" }

        override fun catalogBuildNames(): List<String> = catalog.keys.toList()

        override fun downloadCacheRoot(): ClusterS3Path {
            calls += "bucket"
            return ClusterS3Path.downloadCacheRoot("acct-bucket")
        }
    }

    private fun state(vararg nodes: Pair<String, NodeState>) = ClusterState(name = "c", nodes = mutableMapOf(*nodes))

    private fun node(vararg records: InstalledVersion) = NodeState(installed = records.associateBy { it.version }.toMutableMap())

    private fun resolve(
        sources: FakeSources,
        request: VersionRequest,
        state: ClusterState = state(),
    ): InstallSpec = CassandraVersionResolver(sources).resolve(request, state)

    private val customBuild =
        ProfileBuild(
            version = "my-build",
            url = "https://example.test/my-build.tgz",
            javaSupported = listOf("11", "17"),
            java = "11",
            python = "3.10.4",
        )

    @Nested
    inner class SourceOrder {
        @Test
        fun `a record in the state wins over a profile build of the same name, and reads no other source`() {
            val record = InstalledVersion("my-build", "17", listOf("11", "17"), "3.11.9", CassandraSource.URL, "https://old.test/x.tgz")
            val sources = FakeSources(profile = listOf(customBuild))

            val spec = resolve(sources, VersionRequest("my-build"), state("db0" to node(record)))

            assertThat(spec.source).isEqualTo(CassandraSource.URL)
            assertThat(spec.url).isEqualTo("https://old.test/x.tgz")
            assertThat(sources.calls).isEmpty()
        }

        @Test
        fun `a record on any node is used for a node that has none, so all nodes get the same release`() {
            val record = InstalledVersion("5.0", "17", listOf("11", "17"), "3.11.9", CassandraSource.ASF, AsfReleases.downloadUrl("5.0.5"))
            val sources = FakeSources()

            val spec = resolve(sources, VersionRequest("5.0"), state("db0" to node(record), "db1" to NodeState()))

            assertThat(spec.url).isEqualTo(AsfReleases.downloadUrl("5.0.5"))
            assertThat(spec.fallbackUrl).isEqualTo(AsfReleases.archiveUrl("5.0.5"))
            assertThat(spec.cacheUri).isEqualTo("s3://acct-bucket/download-cache/cassandra-dist/5.0.5/apache-cassandra-5.0.5-bin.tar.gz")
            assertThat(sources.calls).doesNotContain("asf")
        }

        @Test
        fun `a profile build wins over --url, ASF, cassandra-builds and the catalog, and reads none of them`() {
            val sources =
                FakeSources(
                    profile = listOf(customBuild.copy(version = "5.0")),
                    catalog =
                        mapOf(
                            "5.0" to CatalogBuild("s3://b/x", listOf("17")),
                        ),
                )

            val spec = resolve(sources, VersionRequest("5.0"))

            assertThat(spec.source).isEqualTo(CassandraSource.PROFILE)
            assertThat(spec.url).isEqualTo(customBuild.url)
            assertThat(sources.calls).containsExactly("profile")
        }

        @Test
        fun `--url with a profile name replaces the entry's URL, field by field`() {
            val spec = resolve(FakeSources(profile = listOf(customBuild)), VersionRequest("my-build", url = "https://other.test/y.tgz"))

            assertThat(spec.source).isEqualTo(CassandraSource.PROFILE)
            assertThat(spec.url).isEqualTo("https://other.test/y.tgz")
            assertThat(spec.python).isEqualTo("3.10.4")
        }

        @Test
        fun `--url wins over ASF, and reads no public source`() {
            val sources = FakeSources()

            val spec = resolve(sources, VersionRequest("5.0", url = "https://example.test/c.tgz"))

            assertThat(spec.source).isEqualTo(CassandraSource.URL)
            assertThat(spec.url).isEqualTo("https://example.test/c.tgz")
            assertThat(spec.isGitBuild).isFalse()
            assertThat(sources.calls).containsExactly("profile")
        }

        @Test
        fun `--url with --branch is a git build, with the table's ant flags for the JDK`() {
            val spec =
                resolve(FakeSources(), VersionRequest("4.1-mine", url = "https://github.com/me/cassandra.git", branch = "cassandra-4.1"))

            assertThat(spec.source).isEqualTo(CassandraSource.GIT)
            assertThat(spec.branch).isEqualTo("cassandra-4.1")
            assertThat(spec.java).isEqualTo("11")
            assertThat(spec.antFlags).isEqualTo("-Duse.jdk11=true")
        }

        @Test
        fun `--ant-flags replaces the table's flags`() {
            val spec =
                resolve(FakeSources(), VersionRequest("4.1-mine", url = "https://github.com/me/c.git", branch = "b", antFlags = "-Dx=1"))

            assertThat(spec.antFlags).isEqualTo("-Dx=1")
        }

        @ParameterizedTest(name = "{0} installs Apache {1}")
        @CsvSource("5.0, 5.0.6", "4.1, 4.1.7", "4.1.6, 4.1.6", "5.0-rc1, 5.0-rc1")
        fun `an ASF line installs its newest GA release, and an exact release installs itself`(
            name: String,
            release: String,
        ) {
            val spec = resolve(FakeSources(), VersionRequest(name))

            assertThat(spec.name).isEqualTo(name)
            assertThat(spec.source).isEqualTo(CassandraSource.ASF)
            assertThat(spec.url).isEqualTo("${Constants.Cassandra.ASF_DLCDN_BASE}/$release/apache-cassandra-$release-bin.tar.gz")
            assertThat(spec.fallbackUrl).isEqualTo("${Constants.Cassandra.ASF_ARCHIVE_BASE}/$release/apache-cassandra-$release-bin.tar.gz")
            assertThat(
                spec.cacheUri,
            ).isEqualTo("s3://acct-bucket/download-cache/cassandra-dist/$release/apache-cassandra-$release-bin.tar.gz")
        }

        @Test
        fun `a cassandra-builds ref installs its asset, from its origin`() {
            val sources = FakeSources()

            val spec = resolve(sources, VersionRequest("5.0-HEAD"))

            assertThat(spec.source).isEqualTo(CassandraSource.CASSANDRA_BUILDS)
            assertThat(spec.url).isEqualTo("https://gh/5.0-HEAD.tgz")
            assertThat(spec.fallbackUrl).isNull()
            assertThat(spec.cacheUri).isNull()
            assertThat(sources.calls).doesNotContain("catalog")
        }

        @Test
        fun `the catalog is read last, only when no other source has the name`() {
            val sources =
                FakeSources(
                    catalog =
                        mapOf(
                            "5.1-20260905-a1b2c3d-jdk17" to CatalogBuild("s3://b/builds/x.tgz", listOf("17", "21")),
                        ),
                )

            val spec = resolve(sources, VersionRequest("5.1-20260905-a1b2c3d-jdk17"))

            assertThat(spec.source).isEqualTo(CassandraSource.BUILD_CATALOG)
            assertThat(spec.url).isEqualTo("s3://b/builds/x.tgz")
            assertThat(spec.cacheUri).isNull()
            assertThat(sources.calls).containsExactly("profile", "asf", "cassandra-builds", "catalog")
        }

        @Test
        fun `an unknown name fails before any install, naming every source it checked`() {
            assertThatThrownBy { resolve(FakeSources(), VersionRequest("9.9-nope")) }
                .isInstanceOf(VersionResolutionException::class.java)
                .hasMessageContaining(
                    "9.9-nope",
                    "cluster state",
                    "/profile/cassandra_versions",
                    "Apache",
                    "cassandra-builds",
                    "build catalog",
                    "--url",
                )
        }
    }

    @Nested
    inner class AsfRefetch {
        @Test
        fun `an X_Y_Z name missing from the cached list fetches the list once more`() {
            val sources = FakeSources(asfOnRefresh = listOf("5.0.6", "5.0.7"))

            val spec = resolve(sources, VersionRequest("5.0.7"))

            assertThat(spec.url).isEqualTo(AsfReleases.downloadUrl("5.0.7"))
            assertThat(sources.calls.filter { it.startsWith("asf") }).containsExactly("asf", "asf-refresh")
        }

        @Test
        fun `a name that is not an X_Y_Z release causes no refetch`() {
            val sources = FakeSources()

            resolve(sources, VersionRequest("trunk"))

            assertThat(sources.calls).doesNotContain("asf-refresh")
        }

        @Test
        fun `an X_Y_Z name missing after the refetch goes on to the other sources and then fails`() {
            val sources = FakeSources()

            assertThatThrownBy { resolve(sources, VersionRequest("5.0.99")) }.isInstanceOf(VersionResolutionException::class.java)
            assertThat(sources.calls).containsSubsequence("asf", "asf-refresh", "cassandra-builds", "catalog")
        }

        @Test
        fun `an unknown X_Y_Z name says the ASF list could not be fetched again and the cached copy is stale`() {
            val sources = FakeSources(asfRefreshFails = true)

            assertThatThrownBy { resolve(sources, VersionRequest("5.0.99")) }
                .isInstanceOf(VersionResolutionException::class.java)
                .hasMessageContaining("Unknown Cassandra version")
                .hasMessageContaining("The ASF list could not be fetched; the cached copy from ${Instant.EPOCH} was used (stale).")
        }

        @Test
        fun `an unknown name says nothing about staleness when the refetch worked`() {
            assertThatThrownBy { resolve(FakeSources(), VersionRequest("5.0.99")) }.message().doesNotContain("stale")
        }
    }

    @Nested
    inner class Jdk {
        @ParameterizedTest(name = "{0} defaults to JDK {1}")
        @CsvSource("5.0, 17", "4.1, 11", "trunk, 21", "5.0-HEAD, 17", "4.1.6, 11")
        fun `with no --java the highest JDK of the table entry is picked`(
            name: String,
            java: String,
        ) {
            assertThat(resolve(FakeSources(), VersionRequest(name)).java).isEqualTo(java)
        }

        @Test
        fun `--java inside the list is used`() {
            val spec = resolve(FakeSources(), VersionRequest("5.0", java = "11"))

            assertThat(spec.java).isEqualTo("11")
            assertThat(spec.javaSupported).containsExactly("11", "17")
        }

        @Test
        fun `--java outside the list fails, listing the supported JDKs`() {
            assertThatThrownBy { resolve(FakeSources(), VersionRequest("5.0", java = "21")) }
                .isInstanceOf(VersionResolutionException::class.java)
                .hasMessageContaining("21", "5.0", "11, 17")
        }

        @Test
        fun `no list and no --java fails, saying to pass --java`() {
            assertThatThrownBy { resolve(FakeSources(), VersionRequest("mine", url = "https://example.test/m.tgz")) }
                .isInstanceOf(VersionResolutionException::class.java)
                .hasMessageContaining("--java")
        }

        @Test
        fun `no list and --java makes the list that JDK alone`() {
            val spec = resolve(FakeSources(), VersionRequest("mine", url = "https://example.test/m.tgz", java = "25"))

            assertThat(spec.javaSupported).containsExactly("25")
            assertThat(spec.java).isEqualTo("25")
        }

        @Test
        fun `the cassandra-builds manifest beats the table`() {
            val sources = FakeSources(manifests = mapOf("5.0-HEAD" to CassandraBuildsManifest(listOf("11", "17", "21"), "11")))

            val spec = resolve(sources, VersionRequest("5.0-HEAD"))

            assertThat(spec.javaSupported).containsExactly("11", "17", "21")
            assertThat(spec.java).isEqualTo("21")
        }

        @Test
        fun `the catalog manifest's JDK list is used`() {
            val sources = FakeSources(catalog = mapOf("b1" to CatalogBuild("s3://b/b1.tgz", listOf("17", "21"))))

            assertThat(resolve(sources, VersionRequest("b1")).java).isEqualTo("21")
        }

        @Test
        fun `the JDK and Python a node records are kept with no --java`() {
            val record = InstalledVersion("5.0", "11", listOf("11", "17"), "3.10.4", CassandraSource.ASF, AsfReleases.downloadUrl("5.0.6"))
            val cluster = state("db0" to node(record))
            val resolver = CassandraVersionResolver(FakeSources())
            val request = VersionRequest("5.0")

            val spec = resolver.forNode(resolver.resolve(request, cluster), request, cluster.nodes["db0"])

            assertThat(spec.java).isEqualTo("11")
            assertThat(spec.python).isEqualTo("3.10.4")
        }

        @Test
        fun `a node with no record of its own gets the highest JDK, not another node's choice`() {
            val record = InstalledVersion("5.0", "11", listOf("11", "17"), "3.11.9", CassandraSource.ASF, AsfReleases.downloadUrl("5.0.6"))

            assertThat(resolve(FakeSources(), VersionRequest("5.0"), state("db0" to node(record))).java).isEqualTo("17")
        }

        @Test
        fun `--java overrides the JDK a record holds`() {
            val record = InstalledVersion("5.0", "11", listOf("11", "17"), "3.11.9", CassandraSource.ASF, AsfReleases.downloadUrl("5.0.6"))

            val cluster = state("db0" to node(record))
            val resolver = CassandraVersionResolver(FakeSources())
            val request = VersionRequest("5.0", java = "17")

            assertThat(resolver.forNode(resolver.resolve(request, cluster), request, cluster.nodes["db0"]).java).isEqualTo("17")
        }

        @Test
        fun `forNode keeps each node's own recorded JDK`() {
            val on11 = InstalledVersion("5.0", "11", listOf("11", "17"), "3.11.9", CassandraSource.ASF, AsfReleases.downloadUrl("5.0.6"))
            val on17 = on11.copy(java = "17")
            val cluster = state("db0" to node(on11), "db1" to node(on17), "db2" to NodeState())
            val resolver = CassandraVersionResolver(FakeSources())
            val request = VersionRequest("5.0")
            val spec = resolver.resolve(request, cluster)

            assertThat(listOf("db0", "db1", "db2").map { resolver.forNode(spec, request, cluster.nodes[it]).java })
                .containsExactly("11", "17", spec.java)
        }
    }

    @Nested
    inner class ProfileBuilds {
        @Test
        fun `the entry's JDKs and Python replace the manifest and the table, and its java is the default`() {
            val spec = resolve(FakeSources(profile = listOf(customBuild.copy(version = "5.0-HEAD"))), VersionRequest("5.0-HEAD"))

            assertThat(spec.javaSupported).containsExactly("11", "17")
            assertThat(spec.java).isEqualTo("11")
            assertThat(spec.python).isEqualTo("3.10.4")
        }

        @Test
        fun `--java and --python override the entry`() {
            val spec = resolve(FakeSources(profile = listOf(customBuild)), VersionRequest("my-build", java = "17", python = "3.12.1"))

            assertThat(spec.java).isEqualTo("17")
            assertThat(spec.python).isEqualTo("3.12.1")
        }

        @Test
        fun `--java outside the entry's list fails`() {
            assertThatThrownBy { resolve(FakeSources(profile = listOf(customBuild)), VersionRequest("my-build", java = "21")) }
                .isInstanceOf(VersionResolutionException::class.java)
                .hasMessageContaining("11, 17")
        }

        @Test
        fun `an entry with only java runs on that JDK`() {
            val build = ProfileBuild(version = "solo", url = "https://example.test/s.tgz", java = "17")

            val spec = resolve(FakeSources(profile = listOf(build)), VersionRequest("solo"))

            assertThat(spec.javaSupported).containsExactly("17")
        }

        @Test
        fun `an entry with no JDK and no --java fails, saying to pass --java or set java in the entry`() {
            val build = ProfileBuild(version = "5.0-mine", url = "https://example.test/s.tgz")

            assertThatThrownBy { resolve(FakeSources(profile = listOf(build)), VersionRequest("5.0-mine")) }
                .isInstanceOf(VersionResolutionException::class.java)
                .hasMessageContaining("--java", "java", "/profile/cassandra_versions")
        }

        @Test
        fun `an entry with a branch is a git build, with its ant flags`() {
            val build = customBuild.copy(url = "https://github.com/me/c.git", branch = "trunk-x", antFlags = "-Dfoo=1")

            val spec = resolve(FakeSources(profile = listOf(build)), VersionRequest("my-build"))

            assertThat(spec.source).isEqualTo(CassandraSource.PROFILE)
            assertThat(spec.isGitBuild).isTrue()
            assertThat(spec.branch).isEqualTo("trunk-x")
            assertThat(spec.antFlags).isEqualTo("-Dfoo=1")
        }

        @Test
        fun `a profile build gets no cache location, and the bucket is not read`() {
            val sources = FakeSources(profile = listOf(customBuild))

            val spec = resolve(sources, VersionRequest("my-build"))

            assertThat(spec.cacheUri).isNull()
            assertThat(spec.fallbackUrl).isNull()
            assertThat(sources.calls).doesNotContain("bucket")
        }
    }

    @Nested
    inner class PythonAndRecords {
        @Test
        fun `Python is the default with no --python, and --python replaces it`() {
            assertThat(resolve(FakeSources(), VersionRequest("5.0")).python).isEqualTo(Constants.Cassandra.DEFAULT_PYTHON_VERSION)
            assertThat(resolve(FakeSources(), VersionRequest("5.0", python = "3.12.1")).python).isEqualTo("3.12.1")
        }

        @Test
        fun `the cache location is set for an ASF release only`() {
            val sources = FakeSources(catalog = mapOf("b1" to CatalogBuild("s3://b/b1.tgz", listOf("17"))))
            val cacheUris =
                listOf(
                    VersionRequest("5.0"),
                    VersionRequest("trunk"),
                    VersionRequest("b1"),
                    VersionRequest("u", url = "https://example.test/u.tgz", java = "17"),
                ).associate { it.name to resolve(sources, it).cacheUri }

            assertThat(cacheUris.filterValues { it != null }.keys).containsExactly("5.0")
        }

        @Test
        fun `a credential in the URL is passed to the node but redacted in the record`() {
            val url = "https://ghp_secret123@github.com/me/private.git"

            val spec = resolve(FakeSources(), VersionRequest("5.0-mine", url = url, branch = "b"))

            assertThat(spec.url).isEqualTo(url)
            assertThat(spec.toRecord().url).doesNotContain("ghp_secret123").contains("github.com/me/private.git")
            assertThat(spec.toString()).doesNotContain("ghp_secret123")
        }

        @Test
        fun `a reinstall takes --url, --python and --ant-flags over the record`() {
            val record =
                InstalledVersion(
                    "g",
                    "11",
                    listOf("11"),
                    "3.11.9",
                    CassandraSource.GIT,
                    "https://github.com/me/c.git",
                    branch = "b",
                    antFlags = "-Dold=1",
                )

            val spec =
                resolve(
                    FakeSources(),
                    VersionRequest("g", url = "https://github.com/me/d.git", python = "3.12.1", antFlags = "-Dnew=1", reinstall = true),
                    state("db0" to node(record)),
                )

            assertThat(spec.url).isEqualTo("https://github.com/me/d.git")
            assertThat(spec.branch).isEqualTo("b")
            assertThat(spec.python).isEqualTo("3.12.1")
            assertThat(spec.antFlags).isEqualTo("-Dnew=1")
        }

        @Test
        fun `without --reinstall a record keeps its URL and ant flags`() {
            val record =
                InstalledVersion(
                    "g",
                    "11",
                    listOf("11"),
                    "3.11.9",
                    CassandraSource.GIT,
                    "https://github.com/me/c.git",
                    branch = "b",
                    antFlags = "-Dold=1",
                )

            val spec =
                resolve(
                    FakeSources(),
                    VersionRequest("g", url = "https://github.com/me/d.git", antFlags = "-Dnew=1"),
                    state(
                        "db0" to node(record),
                    ),
                )

            assertThat(spec.url).isEqualTo("https://github.com/me/c.git")
            assertThat(spec.antFlags).isEqualTo("-Dold=1")
        }
    }
}
