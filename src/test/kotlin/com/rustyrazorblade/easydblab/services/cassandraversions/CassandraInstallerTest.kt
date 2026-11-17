package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.CassandraSource
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InstalledVersion
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import kotlin.random.Random

/**
 * The shared install path (design D3, D4, D12): what each node does, the script arguments, and the
 * records the caller writes once after every node finished.
 */
class CassandraInstallerTest : BaseKoinTest() {
    private lateinit var nodes: FakeNodes
    private lateinit var outputHandler: BufferedOutputHandler

    @BeforeEach
    fun setup() {
        nodes = FakeNodes()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    private fun installer(
        running: Set<String> = emptySet(),
        uncheckable: Set<String> = emptySet(),
    ) = CassandraInstaller(nodes, HostOperationsService(mock()), FakeCassandraService(running, uncheckable), getKoin().get<EventBus>())

    private val tarball =
        InstallSpec
            .builder("5.0-HEAD")
            .source(CassandraSource.CASSANDRA_BUILDS)
            .url("https://gh.test/apache-cassandra-5.0-HEAD-bin.tar.gz")
            .jdks(listOf("11", "17"), "17")
            .python("3.11.9")
            .build()

    private val gitBuild =
        InstallSpec
            .builder("my-branch")
            .source(CassandraSource.GIT)
            .url("https://tok3n@github.test/me/cassandra.git")
            .jdks(listOf("11"), "11")
            .python("3.11.9")
            .branch("feature")
            .antFlags("-Duse.jdk11=true")
            .build()

    private fun runPlan(
        spec: InstallSpec,
        request: VersionRequest,
        targets: List<ClusterHost>,
        state: ClusterState,
        mode: InstallMode,
        running: Set<String> = emptySet(),
        uncheckable: Set<String> = emptySet(),
    ): InstallPlan = installer(running, uncheckable).plan(request, targets, state, mode) { spec }

    @Nested
    inner class Planning {
        /**
         * One row per case of the decision: the mode, the version on the node (absent, on the
         * disk, or on the disk as the active version with Cassandra running), the node's record
         * (none, the tarball, or a git build with JDK 11), and the requested JDK.
         */
        @ParameterizedTest(name = "{0} node={1} record={2} java={3} -> {4}")
        @CsvSource(
            "INSTALL,   absent,  none, ,   download",
            "INSTALL,   disk,    none, ,   present",
            "INSTALL,   disk,    tar,  11, present",
            "INSTALL,   disk,    git,  ,   present",
            "INSTALL,   disk,    git,  11, present",
            "INSTALL,   disk,    git,  17, mismatch",
            "USE,       disk,    git,  17, present",
            "USE,       absent,  git,  ,   download",
            "REINSTALL, disk,    git,  17, reinstall",
            "REINSTALL, absent,  none, ,   download",
            "REINSTALL, running, tar,  ,   refused-running",
            "INSTALL,   running, tar,  ,   present",
        )
        fun `each node's action follows from its mode, disk, record and Cassandra`(
            mode: InstallMode,
            onNode: String,
            record: String,
            java: String?,
            expected: String,
        ) {
            val spec = if (record == "git") gitBuild else tarball
            if (onNode != "absent") nodes.disk("db0") += spec.name
            val node =
                NodeState(version = spec.name).apply {
                    if (record != "none") installed[spec.name] = spec.toRecord()
                }
            val plan =
                runPlan(
                    spec,
                    VersionRequest(spec.name, java = java),
                    listOf(FakeNodes.host("db0")),
                    ClusterState(name = "c", nodes = mutableMapOf("db0" to node)),
                    mode,
                    running = if (onNode == "running") setOf("db0") else emptySet(),
                )

            val action = plan.actions.single()
            val actual =
                when (action) {
                    is HostAction.Download -> if (action.reinstall) "reinstall" else "download"
                    is HostAction.Present -> "present"
                    is HostAction.Refused ->
                        when (action.error) {
                            is Event.Cassandra.VersionRecordMismatch -> "mismatch"
                            is Event.Cassandra.ReinstallRefusedRunning -> "refused-running"
                            else -> "failed"
                        }
                }
            assertThat(actual).isEqualTo(expected)
        }

        @Test
        fun `a reinstall of a version that is not active is not refused when Cassandra runs`() {
            nodes.disk("db0") += tarball.name
            val node = NodeState(version = "4.1")

            val plan =
                runPlan(
                    tarball,
                    VersionRequest(tarball.name, reinstall = true),
                    listOf(FakeNodes.host("db0")),
                    ClusterState(name = "c", nodes = mutableMapOf("db0" to node)),
                    InstallMode.REINSTALL,
                    running = setOf("db0"),
                )

            assertThat(plan.actions.single()).isInstanceOf(HostAction.Download::class.java)
        }

        /**
         * One row per field a git build records: the requested value, and the difference the plain
         * install reports ("" when the request matches the record). The recorded URL is redacted,
         * so the same URL with its credential is a match, not a difference.
         */
        @ParameterizedTest(name = "{0}={1}")
        @CsvSource(
            "java, 17, 'java: recorded 11, requested 17'",
            "java, 11, ''",
            "ant flags, -Dx=1, 'ant flags: recorded -Duse.jdk11=true, requested -Dx=1'",
            "ant flags, -Duse.jdk11=true, ''",
            "branch, other, 'branch: recorded feature, requested other'",
            "branch, feature, ''",
            "url, https://tok3n@github.test/me/cassandra.git, ''",
            "url, https://other@github.test/me/cassandra.git, ''",
            "url, https://github.test/you/cassandra.git, 'url: recorded https://***@github.test/me/cassandra.git, requested https://github.test/you/cassandra.git'",
        )
        fun `a plain install of a git build reports each recorded field the request contradicts`(
            field: String,
            requested: String,
            expected: String,
        ) {
            nodes.disk("db0") += gitBuild.name
            val node = NodeState(installed = mutableMapOf(gitBuild.name to gitBuild.toRecord()))
            val request =
                when (field) {
                    "java" -> VersionRequest(gitBuild.name, java = requested)
                    "ant flags" -> VersionRequest(gitBuild.name, antFlags = requested)
                    "branch" -> VersionRequest(gitBuild.name, branch = requested)
                    else -> VersionRequest(gitBuild.name, url = requested)
                }

            val action =
                runPlan(
                    gitBuild,
                    request,
                    listOf(FakeNodes.host("db0")),
                    ClusterState(name = "c", nodes = mutableMapOf("db0" to node)),
                    InstallMode.INSTALL,
                ).actions.single()

            val differences =
                when (action) {
                    is HostAction.Refused -> (action.error as Event.Cassandra.VersionRecordMismatch).differences
                    else -> emptyList()
                }
            assertThat(differences).isEqualTo(listOfNotNull(expected.ifEmpty { null }))
            assertThat(action).isInstanceOf(if (expected.isEmpty()) HostAction.Present::class.java else HostAction.Refused::class.java)
        }

        @Test
        fun `a mismatch names every field that disagrees`() {
            nodes.disk("db0") += gitBuild.name
            val node = NodeState(installed = mutableMapOf(gitBuild.name to gitBuild.toRecord()))

            val plan =
                runPlan(
                    gitBuild,
                    VersionRequest(gitBuild.name, java = "17", antFlags = "-Dx=1"),
                    listOf(FakeNodes.host("db0")),
                    ClusterState(name = "c", nodes = mutableMapOf("db0" to node)),
                    InstallMode.INSTALL,
                )

            val error = (plan.actions.single() as HostAction.Refused).error as Event.Cassandra.VersionRecordMismatch
            assertThat(error.differences).containsExactly(
                "java: recorded 11, requested 17",
                "ant flags: recorded -Duse.jdk11=true, requested -Dx=1",
            )
        }

        @Test
        fun `a node that cannot be listed is refused before any node changes, and named`() {
            nodes.unreachable += "db1"

            val plan =
                runPlan(
                    tarball,
                    VersionRequest(tarball.name),
                    listOf(FakeNodes.host("db0"), FakeNodes.host("db1")),
                    ClusterState(name = "c"),
                    InstallMode.USE,
                )

            assertThat(plan.canSucceed).isFalse()
            assertThat(
                plan.refusals
                    .single()
                    .host.alias,
            ).isEqualTo("db1")
        }

        @Test
        fun `a node that must download a build recorded with a redacted credential is refused and told to pass --url`() {
            nodes.disk("db0") += gitBuild.name
            val redacted = gitBuild.toBuilder().url("https://***@github.test/me/cassandra.git").build()

            val plan =
                runPlan(
                    redacted,
                    VersionRequest(redacted.name),
                    listOf(FakeNodes.host("db0"), FakeNodes.host("db1")),
                    ClusterState(name = "c"),
                    InstallMode.USE,
                )

            val refusal = plan.refusals.single()
            assertThat(refusal.host.alias).isEqualTo("db1")
            assertThat((refusal.error as Event.Cassandra.VersionInstallFailed).reason)
                .contains("without its credential", "--reinstall --url")
            assertThat(plan.actions.first { it.host.alias == "db0" }).isInstanceOf(HostAction.Present::class.java)
        }

        @Test
        fun `a reinstall on a node whose running check fails refuses that node and names the check`() {
            val state =
                ClusterState(
                    name = "c",
                    nodes = mutableMapOf("db0" to NodeState(version = tarball.name), "db1" to NodeState(version = tarball.name)),
                )

            val plan =
                runPlan(
                    tarball,
                    VersionRequest(tarball.name, reinstall = true),
                    listOf(FakeNodes.host("db0"), FakeNodes.host("db1")),
                    state,
                    InstallMode.REINSTALL,
                    uncheckable = setOf("db1"),
                )

            val refusal = plan.refusals.single()
            assertThat(refusal.host.alias).isEqualTo("db1")
            assertThat((refusal.error as Event.Cassandra.VersionInstallFailed).reason)
                .startsWith("could not be checked:")
                .contains("systemctl timed out on db1")
            assertThat(plan.actions.first { it.host.alias == "db0" }).isInstanceOf(HostAction.Download::class.java)
        }

        @Test
        fun `planning lists each node and runs no install`() {
            nodes.disk("db0") += tarball.name

            val plan =
                runPlan(
                    tarball,
                    VersionRequest(tarball.name),
                    listOf(FakeNodes.host("db0"), FakeNodes.host("db1")),
                    ClusterState(name = "c"),
                    InstallMode.USE,
                )

            assertThat(plan.downloadHosts).containsExactly("db1")
            assertThat(nodes.commands.map { it.text }).allMatch { it.startsWith("ls ") }
        }
    }

    @Nested
    inner class Command {
        @Test
        fun `an ASF release passes its fallback and its cache object`() {
            val spec =
                InstallSpec
                    .builder("5.0")
                    .source(CassandraSource.ASF)
                    .url(AsfReleases.downloadUrl("5.0.6"))
                    .fallbackUrl(AsfReleases.archiveUrl("5.0.6"))
                    .cacheUri("s3://b/download-cache/cassandra-dist/5.0.6/apache-cassandra-5.0.6-bin.tar.gz")
                    .jdks(listOf("11", "17"), "17")
                    .python("3.11.9")
                    .build()

            assertThat(CassandraInstaller.installCommand(spec, reinstall = false)).isEqualTo(
                "install-cassandra-version 5.0 --url ${AsfReleases.downloadUrl("5.0.6")} " +
                    "--fallback-url ${AsfReleases.archiveUrl("5.0.6")} " +
                    "--cache-uri s3://b/download-cache/cassandra-dist/5.0.6/apache-cassandra-5.0.6-bin.tar.gz",
            )
        }

        @Test
        fun `a tarball outside ASF passes no cache object, so it is fetched from its origin`() {
            assertThat(CassandraInstaller.installCommand(tarball, reinstall = true))
                .isEqualTo("install-cassandra-version 5.0-HEAD --url https://gh.test/apache-cassandra-5.0-HEAD-bin.tar.gz --reinstall")
        }

        @Test
        fun `a git build passes its branch, JDK and ant flags, quoted`() {
            val spec = gitBuild.toBuilder().antFlags("-Da=1 -Db=2").build()

            assertThat(CassandraInstaller.installCommand(spec, reinstall = false)).isEqualTo(
                "install-cassandra-version my-branch --url https://tok3n@github.test/me/cassandra.git " +
                    "--branch feature --java 11 --ant-flags '-Da=1 -Db=2'",
            )
        }
    }

    @Nested
    inner class Records {
        /**
         * The property of D3: whatever subset of nodes fails, the records written once afterwards
         * hold every node that succeeded, and a node that failed keeps the record it had.
         */
        @ParameterizedTest(name = "{0} nodes")
        @ValueSource(ints = [1, 2, 7, 50])
        fun `every node that succeeded is recorded and every failed node keeps its old record`(count: Int) {
            val random = Random(count)
            val hosts = (0 until count).map { FakeNodes.host("db$it") }
            // db0 always fails and, past one node, the last always succeeds, so every fleet has both.
            val failing =
                hosts
                    .filterIndexed { i, _ -> i == 0 || (i < count - 1 && random.nextInt(4) == 0) }
                    .map { it.alias }
                    .toSet()
            failing.forEach { nodes.installFailures[it] = "curl: (22) 404" }
            val old =
                InstalledVersion(
                    tarball.name,
                    "11",
                    listOf("11", "17"),
                    "3.11.9",
                    CassandraSource.CASSANDRA_BUILDS,
                    "https://old.test/x.tgz",
                )
            val state =
                ClusterState(
                    name = "c",
                    nodes =
                        hosts
                            .associate {
                                it.alias to
                                    NodeState(installed = mutableMapOf(tarball.name to old))
                            }.toMutableMap(),
                )

            val installer = installer()
            val plan =
                installer.plan(
                    VersionRequest(tarball.name, reinstall = true),
                    hosts,
                    state,
                    InstallMode.REINSTALL,
                ) { tarball }
            val report = installer.execute(plan, reportPresent = true)
            report.recordInto(state)

            assertThat(report.ready.map { it.host.alias }).containsExactlyInAnyOrderElementsOf(hosts.map { it.alias } - failing)
            hosts.forEach { host ->
                val expected = if (host.alias in failing) old else tarball.toRecord()
                assertThat(state.nodes.getValue(host.alias).installed[tarball.name]).describedAs(host.alias).isEqualTo(expected)
            }
        }

        @Test
        fun `a node with the version on its disk and no record gets a record and no download`() {
            nodes.disk("db0") += tarball.name
            val state = ClusterState(name = "c")

            val installer = installer()
            val report =
                installer.execute(
                    installer.plan(
                        VersionRequest(tarball.name),
                        listOf(FakeNodes.host("db0")),
                        state,
                        InstallMode.INSTALL,
                    ) {
                        tarball
                    },
                    true,
                )
            report.recordInto(state)

            assertThat(nodes.scriptRuns("db0")).isEmpty()
            assertThat(state.nodes.getValue("db0").installed[tarball.name]).isEqualTo(tarball.toRecord())
            assertThat(outputHandler.messages.joinToString("\n")).contains("db0: 5.0-HEAD is already installed")
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `a failed node is reported with the version, its URLs and the reason, and the others still install`() {
            nodes.installFailures["db1"] = "ERROR: Failed to download version 5.0-HEAD"
            val hosts = listOf(FakeNodes.host("db0"), FakeNodes.host("db1"))

            val installer = installer()
            val report =
                installer.execute(
                    installer.plan(
                        VersionRequest(tarball.name),
                        hosts,
                        ClusterState(name = "c"),
                        InstallMode.USE,
                    ) {
                        tarball
                    },
                    false,
                )

            assertThat(nodes.disk("db0")).contains(tarball.name)
            val failure = report.failed.single().error as Event.Cassandra.VersionInstallFailed
            assertThat(failure.host).isEqualTo("db1")
            assertThat(failure.urls).containsExactly(tarball.url)
            assertThat(failure.reason).contains("Failed to download version 5.0-HEAD")
            assertThat(report.failureMessage()).contains("db1", "5.0-HEAD", tarball.url)
        }

        @Test
        fun `a credential in a git url reaches no event and no failure message`() {
            nodes.installFailures["db0"] = "ERROR: Git clone failed for version my-branch from https://tok3n@github.test/me/cassandra.git"

            val installer = installer()
            val plan =
                installer.plan(
                    VersionRequest(gitBuild.name),
                    listOf(FakeNodes.host("db0")),
                    ClusterState(name = "c"),
                    InstallMode.INSTALL,
                ) {
                    gitBuild
                }
            val report = installer.execute(plan, false)

            val everything =
                (
                    outputHandler.messages +
                        outputHandler.errors.map {
                            it.first
                        } + listOfNotNull(report.failureMessage())
                ).joinToString("\n")
            assertThat(everything).contains("my-branch").doesNotContain("tok3n")
            assertThat(gitBuild.toRecord().url).doesNotContain("tok3n")
        }
    }
}
