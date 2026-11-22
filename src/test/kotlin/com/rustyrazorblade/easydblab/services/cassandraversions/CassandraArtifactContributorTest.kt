package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CassandraSource
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitContribution
import com.rustyrazorblade.easydblab.services.NodeAllocatable
import com.rustyrazorblade.easydblab.services.RoleNodes
import com.rustyrazorblade.easydblab.services.aws.S3Uri
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant

/**
 * The `cassandra-artifact` contributor: what the cassandra-pods kit's `start` and `upgrade` get from
 * the CLI, resolved before any resource exists.
 */
class CassandraArtifactContributorTest {
    private val asfSha = "a".repeat(64)
    private val nightlySha = "b".repeat(64)

    @TempDir
    lateinit var workspace: File

    private lateinit var kitDir: File

    private val k8s: K8sService = mock()
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

    /** The ASF line 5.0 and whatever cassandra-builds builds a test gives; records which source was read. */
    private class Sources(
        val nightly: List<CassandraBuild> = emptyList(),
    ) : VersionSources {
        val calls = mutableListOf<String>()

        override val profileBuildDir = File("/profile/cassandra_versions")

        override fun profileBuild(name: String): ProfileBuild? = null

        override fun profileBuilds(): List<ProfileBuild> = emptyList()

        override fun asfReleases(refresh: Boolean): Cached<AsfReleases> {
            calls += "asf"
            return Cached(AsfReleases(listOf("5.0.6")), Instant.EPOCH, stale = false)
        }

        override fun cassandraBuilds(refresh: Boolean): Cached<List<CassandraBuild>> {
            calls += "cassandra-builds"
            return Cached(nightly, Instant.EPOCH, stale = false)
        }

        override fun cassandraBuildsManifest(build: CassandraBuild): CassandraBuildsManifest? = null

        override fun catalogBuild(name: String): CatalogBuild? = null.also { calls += "catalog" }

        override fun catalogBuildNames(): List<String> = emptyList()

        override fun presignCatalogTarball(
            name: String,
            ttl: Duration,
        ): String = error("not used")

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

    @BeforeEach
    fun setUp() {
        kitDir = File(workspace, "cassandra-pods").apply { mkdirs() }
        whenever(k8s.nodesMatching(any())).thenReturn(Result.success(listOf("n1", "n2", "n3")))
        allocatable(NodeAllocatable(memoryMib = 30000, cpu = 8))
    }

    private fun allocatable(node: NodeAllocatable) {
        whenever(k8s.nodeAllocatable(any())).thenReturn(Result.success(listOf(node)))
    }

    private fun contributor(sources: Sources = Sources()) =
        CassandraArtifactContributor(
            CassandraTarballResolver(CassandraVersionResolver(sources), sources, TarballDigests(Http(), bus), Duration.ofHours(3)),
            RoleNodes(k8s),
            bus,
        )

    private val owned = ClusterState(name = "lab", environment = Environment.Ec2K3s)
    private val local = ClusterState(name = "lab", environment = Environment.K3sDocker("/d"))

    private fun args(vararg given: Pair<String, String>): Map<String, String> =
        mapOf(
            "CASSANDRA_VERSION" to "5.0",
            "CASSANDRA_BUILD" to "",
            "CASSANDRA_JAVA" to "",
            "CASSANDRA_IMAGE" to "",
            "CASSANDRA_MEMORY" to "",
            "CASSANDRA_CPUS" to "",
            "CASSANDRA_DC" to "dc1",
        ) + given

    private fun contribute(
        args: Map<String, String> = args(),
        state: ClusterState = owned,
        sources: Sources = Sources(),
    ): KitContribution = contributor(sources).contribute(args, state, kitDir)

    @Test
    fun `it is the contributor kit yaml names cassandra-artifact`() {
        assertThat(contributor().name).isEqualTo(Constants.Kit.Contributors.CASSANDRA_ARTIFACT)
    }

    @Test
    fun `--version resolves to the ASF tarball and the runtime image of its JDK`() {
        val variables = contribute().variables

        assertThat(variables).containsAllEntriesOf(
            mapOf(
                "EDL_TARBALL_URL" to AsfReleases.downloadUrl("5.0.6"),
                "EDL_TARBALL_FALLBACK_URL" to AsfReleases.archiveUrl("5.0.6"),
                "EDL_TARBALL_SHA256" to asfSha,
                "EDL_CASSANDRA_BUILD" to "5.0.6",
                "EDL_RUNTIME_IMAGE" to
                    "${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REPOSITORY}:${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION}-jre17",
            ),
        )
    }

    /** `start` says what it is about to run, as `upgrade` says what it rolls from and to. */
    @Test
    fun `the resolved build, its sha256, source and runtime tag are reported as one event`() {
        contribute(args("CASSANDRA_JAVA" to "11"))

        val resolved = events.filterIsInstance<Event.Cassandra.TarballResolved>().single()
        assertThat(resolved.build).isEqualTo("5.0.6")
        assertThat(resolved.sha256).isEqualTo(asfSha)
        assertThat(resolved.source).isEqualTo(CassandraSource.ASF)
        assertThat(resolved.runtimeTag).isEqualTo("${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION}-jre11")
    }

    @Test
    fun `a cassandra-builds build is reported with its source`() {
        val sources = Sources(nightly = listOf(CassandraBuild("5.0-HEAD", "https://gh/5.0-HEAD.tgz", digest = "sha256:$nightlySha")))

        contribute(args("CASSANDRA_BUILD" to "5.0-HEAD"), sources = sources)

        val resolved = events.filterIsInstance<Event.Cassandra.TarballResolved>().single()
        assertThat(resolved.source).isEqualTo(CassandraSource.CASSANDRA_BUILDS)
        assertThat(resolved.build).isEqualTo("5.0-HEAD")
    }

    @Test
    fun `an --image start resolves no tarball and reports none`() {
        contribute(args("CASSANDRA_IMAGE" to "ghcr.io/x/cassandra:sha-abc"))

        assertThat(events.filterIsInstance<Event.Cassandra.TarballResolved>()).isEmpty()
    }

    @Test
    fun `--java picks the runtime image tag`() {
        assertThat(contribute(args("CASSANDRA_JAVA" to "11")).variables["EDL_RUNTIME_IMAGE"]).endsWith("-jre11")
    }

    @Test
    fun `the artifact is written to resolved-artifact env, single-quoted so sh can source a presigned URL`() {
        val file = contribute().files.getValue(Constants.Kit.CassandraPods.ARTIFACT_FILE)

        assertThat(file.lines()).contains("EDL_TARBALL_SHA256='$asfSha'", "EDL_CASSANDRA_BUILD='5.0.6'").last().isEqualTo("")
    }

    @Test
    fun `--build reads cassandra-builds alone and never ASF, even for a release line's name`() {
        val sources = Sources(nightly = listOf(CassandraBuild("5.0-HEAD", "https://gh/5.0-HEAD.tgz", digest = "sha256:$nightlySha")))

        val variables = contribute(args("CASSANDRA_BUILD" to "5.0-HEAD"), sources = sources).variables

        assertThat(variables).containsEntry("EDL_TARBALL_URL", "https://gh/5.0-HEAD.tgz").containsEntry("EDL_TARBALL_FALLBACK_URL", "")
        assertThat(sources.calls).containsOnly("cassandra-builds")

        val empty = Sources()
        assertThatThrownBy { contribute(args("CASSANDRA_BUILD" to "5.0"), sources = empty) }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("cassandra-builds")
            .hasMessageContaining("'5.0'")
        assertThat(empty.calls).doesNotContain("asf")
    }

    @Test
    fun `a build with no digest fails, naming its tarball URL`() {
        val sources = Sources(nightly = listOf(CassandraBuild("trunk", "https://gh/trunk.tgz")))

        assertThatThrownBy { contribute(args("CASSANDRA_BUILD" to "trunk"), sources = sources) }
            .hasMessageContaining("https://gh/trunk.tgz")
    }

    @Test
    fun `--image skips resolution, whatever --version is`() {
        val sources = Sources()

        val contribution =
            contribute(args("CASSANDRA_IMAGE" to "ghcr.io/x/cassandra:sha-abc", "CASSANDRA_VERSION" to "9.9.9"), sources = sources)

        assertThat(sources.calls).isEmpty()
        assertThat(contribution.variables.keys).noneMatch { it.startsWith("EDL_TARBALL") }
        assertThat(contribution.files).doesNotContainKey(Constants.Kit.CassandraPods.ARTIFACT_FILE)
    }

    @Test
    fun `--build together with --image is refused, naming both, before anything is resolved`() {
        val sources = Sources()

        assertThatThrownBy { contribute(args("CASSANDRA_BUILD" to "5.0-HEAD", "CASSANDRA_IMAGE" to "x:y"), sources = sources) }
            .hasMessageContaining("--build 5.0-HEAD")
            .hasMessageContaining("--image x:y")
        assertThat(sources.calls).isEmpty()
    }

    @Test
    fun `an unknown version fails naming it`() {
        assertThatThrownBy { contribute(args("CASSANDRA_VERSION" to "9.9.9")) }.hasMessageContaining("9.9.9")
    }

    @Test
    fun `the placement patch seeds from pod 0 and uses the claim, the gossiping snitch and the NodePort`() {
        val placement = contribute().files.getValue(Constants.Kit.CassandraPods.PLACEMENT_FILE)

        assertThat(placement).contains(
            "cassandra-pods-0.cassandra-pods-gossip.default.svc.cluster.local",
            "/var/lib/cassandra/data",
            "GossipingPropertyFileSnitch",
            "native_transport_port: 30942",
        )
    }

    @Test
    fun `cassandra-rackdc properties names the --dc and rack1`() {
        val rackdc = contribute(args("CASSANDRA_DC" to "east")).files.getValue(Constants.Kit.CassandraPods.RACKDC_FILE)

        assertThat(rackdc).isEqualTo("dc=east\nrack=rack1\n")
    }

    @Test
    fun `with no workspace patch the common patch is written for the cluster`() {
        val patch = contribute().files.getValue(Constants.ConfigPaths.CASSANDRA_PATCH_FILE)

        assertThat(patch).contains("cluster_name:", "lab", "num_tokens: 4", "trickle_fsync: true")
    }

    @Test
    fun `the workspace patch, with the user's edits, is the one the pods get`() {
        File(workspace, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).writeText("concurrent_reads: 128\n")

        assertThat(contribute().files.getValue(Constants.ConfigPaths.CASSANDRA_PATCH_FILE)).isEqualTo("concurrent_reads: 128\n")
    }

    @Test
    fun `the config hash is stable while nothing changes`() {
        File(kitDir, "config").mkdirs()
        File(kitDir, "config/render-config.sh").writeText("render v1\n")

        assertThat(contribute().variables["EDL_CONFIG_HASH"]).isNotBlank().isEqualTo(contribute().variables["EDL_CONFIG_HASH"])
    }

    /** A catalog build is signed again on every start; a new signature alone must not roll the pods. */
    @Test
    fun `the config hash ignores the tarball URLs, so a re-signed URL rolls nothing`() {
        val first = Sources(nightly = listOf(CassandraBuild("5.0-HEAD", "https://gh/x.tgz?sig=1", digest = "sha256:$nightlySha")))
        val resigned = Sources(nightly = listOf(CassandraBuild("5.0-HEAD", "https://gh/x.tgz?sig=2", digest = "sha256:$nightlySha")))

        assertThat(contribute(args("CASSANDRA_BUILD" to "5.0-HEAD"), sources = first).variables["EDL_CONFIG_HASH"])
            .isEqualTo(contribute(args("CASSANDRA_BUILD" to "5.0-HEAD"), sources = resigned).variables["EDL_CONFIG_HASH"])
    }

    @Test
    fun `the config hash changes with the workspace patch, the ConfigMap's scripts and the artifact`() {
        File(kitDir, "config").mkdirs()
        File(kitDir, "config/render-config.sh").writeText("render v1\n")
        val before = contribute().variables.getValue("EDL_CONFIG_HASH")

        File(workspace, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).writeText("concurrent_reads: 128\n")
        val patched = contribute().variables.getValue("EDL_CONFIG_HASH")
        File(kitDir, "config/render-config.sh").writeText("render v2\n")
        val scripted = contribute().variables.getValue("EDL_CONFIG_HASH")
        val otherJdk = contribute(args("CASSANDRA_JAVA" to "11")).variables.getValue("EDL_CONFIG_HASH")

        assertThat(listOf(before, patched, scripted, otherJdk)).doesNotHaveDuplicates()
    }

    @Test
    fun `on owned nodes the memory defaults to 75 percent of the smallest db node, rounded down to Mi`() {
        allocatable(NodeAllocatable(memoryMib = 30001, cpu = 8))

        assertThat(contribute().variables).containsEntry("CASSANDRA_MEMORY", "22500Mi").containsEntry("CASSANDRA_CPUS", "7")
    }

    @Test
    fun `elsewhere the memory defaults to 3Gi`() {
        assertThat(contribute(state = local).variables).containsEntry("CASSANDRA_MEMORY", "3Gi")
    }

    @Test
    fun `the CPU default is at least 1`() {
        allocatable(NodeAllocatable(memoryMib = 4000, cpu = 1))

        assertThat(contribute(state = local).variables).containsEntry("CASSANDRA_CPUS", "1")
    }

    private fun resources(json: String?): Map<String, Map<String, String>> =
        Json.decodeFromString<Map<String, Map<String, String>>>(requireNotNull(json))

    @Test
    fun `on Guaranteed nodes every container's requests equal its limits`() {
        val variables = contribute().variables
        val cassandra = resources(variables["EDL_CASSANDRA_RESOURCES"])
        val init = resources(variables["EDL_INIT_RESOURCES"])

        assertThat(cassandra.getValue("requests")).isEqualTo(mapOf("cpu" to "7", "memory" to "22500Mi"))
        assertThat(cassandra.getValue("limits")).isEqualTo(cassandra.getValue("requests"))
        assertThat(init.getValue("requests")).containsOnlyKeys("cpu", "memory")
        assertThat(init.getValue("limits")).isEqualTo(init.getValue("requests"))
    }

    @Test
    fun `elsewhere the cassandra container requests CPU with no CPU limit, and init containers set nothing`() {
        val variables = contribute(state = local).variables
        val cassandra = resources(variables["EDL_CASSANDRA_RESOURCES"])

        assertThat(cassandra.getValue("requests")).isEqualTo(mapOf("cpu" to "7", "memory" to "3Gi"))
        assertThat(cassandra.getValue("limits")).isEqualTo(mapOf("memory" to "3Gi"))
        assertThat(resources(variables["EDL_INIT_RESOURCES"])).isEmpty()
    }

    @Test
    fun `the --image path gets the same sizing`() {
        val variables = contribute(args("CASSANDRA_IMAGE" to "x:y")).variables

        assertThat(resources(variables["EDL_CASSANDRA_RESOURCES"]).getValue("limits")).containsEntry("cpu", "7")
    }

    @Test
    fun `an explicit --memory and --cpus are kept`() {
        val variables = contribute(args("CASSANDRA_MEMORY" to "8Gi", "CASSANDRA_CPUS" to "3")).variables

        assertThat(variables).containsEntry("CASSANDRA_MEMORY", "8Gi").containsEntry("CASSANDRA_CPUS", "3")
    }
}
