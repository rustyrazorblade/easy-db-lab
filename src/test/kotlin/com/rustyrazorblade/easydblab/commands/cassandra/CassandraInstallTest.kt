package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.exceptions.CommandExecutionException
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * Covers version resolution precedence and the per-host push-up/install/report flow of
 * `cassandra install`.
 */
class CassandraInstallTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var hostOperationsService: HostOperationsService
    private lateinit var outputHandler: BufferedOutputHandler

    /** Contents of each host's /etc/cassandra_versions.yaml, keyed by alias. */
    private val nodeVersionsFile = mutableMapOf<String, String>()

    private fun clusterHost(alias: String) =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.1",
            alias = alias,
            availabilityZone = "us-west-2a",
            instanceId = "i-$alias",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(clusterHost("db0"), clusterHost("db1")),
                ),
        )

    private val declaredVersions =
        listOf(
            CassandraVersion(
                version = "5.1-test",
                java = "11",
                python = "3.12.0",
                jvmOptions = null,
                url = "https://example.com/apache-cassandra-5.1-test-bin.tar.gz",
                antFlags = "-Duse.jdk11=true",
            ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<RemoteOperationsService> { mockRemoteOps }
                single { hostOperationsService }
            },
        )

    /** Versions actually present under /usr/local/cassandra on each host, keyed by alias. */
    private val installedOnNode = mutableMapOf<String, MutableSet<String>>()

    /** Aliases whose install invocation should fail, and with what. */
    private val installFailures = mutableMapOf<String, RuntimeException>()

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        mockRemoteOps = mock()
        hostOperationsService = HostOperationsService(mockClusterStateManager)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockClusterStateManager.exists()).thenReturn(true)

        nodeVersionsFile["db0"] = INSTALLED_VERSIONS_YAML
        nodeVersionsFile["db1"] = INSTALLED_VERSIONS_YAML
        installedOnNode["db0"] = mutableSetOf("5.0")
        installedOnNode["db1"] = mutableSetOf("5.0")

        doAnswer { invocation ->
            val host = invocation.getArgument<Host>(0)
            val command = invocation.getArgument<String>(1)
            when {
                command.startsWith("test -d ") -> {
                    val version = command.substringAfter("/usr/local/cassandra/").substringBefore(" ")
                    val present = version in installedOnNode.getValue(host.alias)
                    Response(if (present) "installed" else "")
                }

                command.startsWith("install-cassandra-version") -> {
                    installFailures[host.alias]?.let { throw it }
                    Response("")
                }

                else -> Response("")
            }
        }.whenever(mockRemoteOps).executeRemotely(any(), any(), any(), any())

        doAnswer { invocation ->
            val host = invocation.getArgument<Host>(0)
            val local = invocation.getArgument<Path>(2)
            Files.writeString(local, nodeVersionsFile.getValue(host.alias))
        }.whenever(mockRemoteOps).download(any(), any(), any())
    }

    /** A command configured for the zero-yaml-edit path: every field supplied as a CLI flag. */
    private fun flagDrivenInstall(version: String = "5.1-test") =
        CassandraInstall().apply {
            this.version = version
            javaVersion = "11"
            url = "https://example.com/apache-cassandra-5.1-test-bin.tar.gz"
            antFlags = "-Duse.jdk11=true"
        }

    @Test
    fun `resolveVersion prefers CLI flags over the declared entry`() {
        val command =
            CassandraInstall().apply {
                version = "5.1-test"
                javaVersion = "17"
                url = "https://example.com/override.tar.gz"
                antFlags = "-Dno.tests=true"
            }

        val resolved = command.resolveVersion(declaredVersions)

        assertThat(resolved.java).isEqualTo("17")
        assertThat(resolved.url).isEqualTo("https://example.com/override.tar.gz")
        assertThat(resolved.antFlags).isEqualTo("-Dno.tests=true")
        // no flag touched python, so it still comes from the declared entry
        assertThat(resolved.python).isEqualTo("3.12.0")
    }

    @Test
    fun `resolveVersion falls back to the declared entry when no flags are given`() {
        val resolved = CassandraInstall().apply { version = "5.1-test" }.resolveVersion(declaredVersions)

        assertThat(resolved.version).isEqualTo("5.1-test")
        assertThat(resolved.java).isEqualTo("11")
        assertThat(resolved.python).isEqualTo("3.12.0")
        assertThat(resolved.url).isEqualTo("https://example.com/apache-cassandra-5.1-test-bin.tar.gz")
        assertThat(resolved.antFlags).isEqualTo("-Duse.jdk11=true")
    }

    @Test
    fun `resolveVersion defaults python when neither source supplies it`() {
        val command =
            CassandraInstall().apply {
                version = "6.0-experiment"
                javaVersion = "21"
            }

        val resolved = command.resolveVersion(declaredVersions)

        assertThat(resolved.python).isEqualTo("3.11.9")
        assertThat(resolved.java).isEqualTo("21")
        assertThat(resolved.url).isNull()
    }

    @Test
    fun `resolveVersion fails when neither source supplies java`() {
        val command = CassandraInstall().apply { version = "6.0-experiment" }

        assertThatThrownBy { command.resolveVersion(declaredVersions) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("6.0-experiment")
            .hasMessageContaining("--java")
    }

    @Test
    fun `resolveVersion rejects a branch without a url`() {
        val command =
            CassandraInstall().apply {
                version = "6.0-experiment"
                javaVersion = "21"
                branch = "trunk"
            }

        assertThatThrownBy { command.resolveVersion(declaredVersions) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--url")
    }

    @Test
    fun `execute repairs a missing declaration for a version already on disk`() {
        // On disk but undeclared: use-cassandra hard-exits without java/python, and there is no
        // existing declaration for these flags to contradict, so this one is safe to write.
        installedOnNode["db0"] = mutableSetOf("5.0", "5.1-test")
        installedOnNode["db1"] = mutableSetOf("5.0", "5.1-test")
        val pushedByHost = capturePushes()

        flagDrivenInstall().execute()

        assertThat(executedCommands()).noneMatch { it.contains("install-cassandra-version") }
        assertThat(outputHandler.messages.joinToString("\n")).contains("already installed")
        val written = CassandraVersion.loadFromFile(writeToTemp(pushedByHost.first().second))
        assertThat(written.single { it.version == "5.1-test" }.java).isEqualTo("11")
    }

    @Test
    fun `execute tells the operator when flags are ignored on an already-installed version`() {
        // Installed with --java 11 yesterday, re-run with --java 17 today. The bits on disk were
        // built with the old parameters, so nothing is changed — but staying silent would leave the
        // operator to discover it later through a wrong-JDK `cassandra use`.
        installedOnNode["db0"] = mutableSetOf("5.0", "5.1-test")
        installedOnNode["db1"] = mutableSetOf("5.0", "5.1-test")
        nodeVersionsFile["db0"] = INSTALLED_VERSIONS_YAML + MATCHING_ENTRY_YAML
        nodeVersionsFile["db1"] = INSTALLED_VERSIONS_YAML + MATCHING_ENTRY_YAML

        assertThatThrownBy { flagDrivenInstall().apply { javaVersion = "17" }.execute() }
            .isInstanceOf(CommandExecutionException::class.java)
            .hasMessageContaining("5.1-test")

        val reported = outputHandler.errors.map { it.first }
        assertThat(reported).anyMatch { it.contains("db0") && it.contains("java") }
        assertThat(reported).anyMatch { it.contains("declared 11") && it.contains("requested 17") }
        // nothing was touched: not reinstalled, and the declaration still describes what is on disk
        assertThat(executedCommands()).noneMatch { it.contains("install-cassandra-version") }
        verify(mockRemoteOps, never()).upload(any(), any(), any())
    }

    @Test
    fun `execute stays quiet when an already-installed version matches what was asked for`() {
        installedOnNode["db0"] = mutableSetOf("5.0", "5.1-test")
        installedOnNode["db1"] = mutableSetOf("5.0", "5.1-test")
        nodeVersionsFile["db0"] = INSTALLED_VERSIONS_YAML + MATCHING_ENTRY_YAML
        nodeVersionsFile["db1"] = INSTALLED_VERSIONS_YAML + MATCHING_ENTRY_YAML

        flagDrivenInstall().execute()

        assertThat(outputHandler.messages.joinToString("\n")).contains("already installed")
        assertThat(outputHandler.errors).isEmpty()
    }

    @Test
    fun `execute stays quiet when an already-installed HEAD version has a baked url on disk`() {
        // Baked HEAD-tracking entries (5.0-HEAD, trunk, ...) legitimately carry a url/branch on
        // disk from the AMI bake. nodeDeclaration always strips url/branch to null before
        // comparing, so a non-null url on the declared entry must not read as a mismatch — only
        // java/python/antFlags matter here.
        installedOnNode["db0"] = mutableSetOf("5.0", "5.1-test")
        installedOnNode["db1"] = mutableSetOf("5.0", "5.1-test")
        nodeVersionsFile["db0"] = INSTALLED_VERSIONS_YAML + MATCHING_ENTRY_WITH_URL_YAML
        nodeVersionsFile["db1"] = INSTALLED_VERSIONS_YAML + MATCHING_ENTRY_WITH_URL_YAML

        flagDrivenInstall().execute()

        assertThat(outputHandler.errors).isEmpty()
        assertThat(outputHandler.messages.joinToString("\n")).contains("already installed")
        assertThat(executedCommands()).noneMatch { it.contains("install-cassandra-version") }
    }

    @Test
    fun `execute installs a lazy version the node declares but has never installed`() {
        // Every AMI ships the whole cassandra_versions.yaml, lazy entries included, so a lazy
        // version is always already declared on the node — that must not read as "installed".
        nodeVersionsFile["db0"] = INSTALLED_VERSIONS_YAML + DECLARED_ENTRY_YAML
        nodeVersionsFile["db1"] = INSTALLED_VERSIONS_YAML + DECLARED_ENTRY_YAML

        flagDrivenInstall().execute()

        val installs = executedCommands().filter { it.contains("install-cassandra-version") }
        assertThat(installs).hasSize(2)
        assertThat(outputHandler.messages.joinToString("\n")).contains("installed 5.1-test")
    }

    @Test
    fun `execute pushes the resolved entry and runs the install script on every host`() {
        flagDrivenInstall().execute()

        verify(mockRemoteOps, times(2)).upload(any(), any(), any())

        val installs = executedCommands().filter { it.contains("install-cassandra-version") }
        assertThat(installs).hasSize(2)
        assertThat(installs.first())
            .contains("install-cassandra-version 5.1-test")
            .contains("--java 11")
            .contains("--url https://example.com/apache-cassandra-5.1-test-bin.tar.gz")
            .contains("--ant-flags -Duse.jdk11=true")
    }

    @Test
    fun `execute appends the new version to the file it pushes back`() {
        val pushed = mutableListOf<String>()
        doAnswer { invocation ->
            pushed.add(Files.readString(invocation.getArgument<Path>(1)))
        }.whenever(mockRemoteOps).upload(any(), any(), any())

        flagDrivenInstall().execute()

        val written = CassandraVersion.loadFromFile(writeToTemp(pushed.first()))
        assertThat(written.map { it.version }).containsExactly("5.0", "5.1-test")
        assertThat(written.single { it.version == "5.1-test" }.java).isEqualTo("11")
        assertThat(written.single { it.version == "5.1-test" }.python).isEqualTo("3.11.9")
    }

    @Test
    fun `execute only touches the hosts named by --hosts`() {
        flagDrivenInstall().apply { hosts.hostList = "db1" }.execute()

        val installedHosts = executedHosts().filterIndexed { index, _ -> executedCommands()[index].contains("install-") }
        assertThat(installedHosts).containsExactly("db1")
        verify(mockRemoteOps, times(1)).upload(any(), any(), any())
        verify(mockRemoteOps, never()).download(argThat { alias == "db0" }, any(), any())
    }

    @Test
    fun `execute refuses to report success when the cluster has no Cassandra nodes`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                initConfig = InitConfig(region = "us-west-2"),
                hosts = mapOf(ServerType.Control to listOf(clusterHost("control0"))),
            ),
        )

        assertThatThrownBy { flagDrivenInstall().execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no Cassandra nodes")
    }

    @Test
    fun `execute refuses an unmatched --hosts filter`() {
        assertThatThrownBy { flagDrivenInstall().apply { hosts.hostList = "db7" }.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("db7")
            .hasMessageContaining("db0, db1")
    }

    @Test
    fun `execute installs a declared git-branch version with its build flags`() {
        val branchEntry =
            CassandraVersion(
                version = "cep-45",
                java = "17",
                python = "3.11.9",
                jvmOptions = null,
                url = "https://github.com/apache/cassandra.git",
                branch = "cep-45",
                antFlags = "-Duse.jdk17=true",
            )

        val resolved = CassandraInstall().apply { version = "cep-45" }.resolveVersion(listOf(branchEntry))

        assertThat(resolved.url).isEqualTo("https://github.com/apache/cassandra.git")
        assertThat(resolved.branch).isEqualTo("cep-45")

        CassandraInstall()
            .apply {
                version = "cep-45"
                url = branchEntry.url.orEmpty()
                branch = branchEntry.branch.orEmpty()
                javaVersion = branchEntry.java
                antFlags = branchEntry.antFlags.orEmpty()
            }.execute()

        val install = executedCommands().first { it.contains("install-cassandra-version") }
        assertThat(install)
            .contains("install-cassandra-version cep-45")
            .contains("--url https://github.com/apache/cassandra.git")
            .contains("--branch cep-45")
            .contains("--java 17")
            .contains("--ant-flags -Duse.jdk17=true")
    }

    @Test
    fun `execute fails loudly when one host fails but still runs every host`() {
        installFailures["db1"] = RuntimeException("ant build failed")

        assertThatThrownBy { flagDrivenInstall().execute() }
            .isInstanceOf(CommandExecutionException::class.java)
            .hasMessageContaining("db1")
            // only the failure applies here; the mismatch half must not appear, empty or otherwise
            .hasMessageNotContaining("null")
            .hasMessageNotContaining("different parameters")

        val installs = executedCommands().filter { it.contains("install-cassandra-version") }
        assertThat(installs).hasSize(2)
        assertThat(outputHandler.errors.map { it.first })
            .anyMatch { it.contains("db1") && it.contains("ant build failed") }
    }

    @Test
    fun `a failed install leaves the declaration in place so a retry can proceed`() {
        installFailures["db1"] = RuntimeException("404 fetching tarball")
        val pushedByHost = capturePushes()

        assertThatThrownBy { flagDrivenInstall().execute() }
            .isInstanceOf(CommandExecutionException::class.java)

        // Declared-but-uninstalled is the harmless state — it is exactly what a lazy entry looks
        // like. Installed-but-undeclared is the unrecoverable one, so nothing is rolled back.
        val db1Pushes = pushedByHost.filter { it.first == "db1" }.map { it.second }
        assertThat(db1Pushes).hasSize(1)
        assertThat(CassandraVersion.loadFromFile(writeToTemp(db1Pushes.single())).map { it.version })
            .contains("5.1-test")
    }

    @Test
    fun `the pushed entry carries no credential`() {
        val pushedByHost = capturePushes()

        CassandraInstall()
            .apply {
                version = "fork"
                javaVersion = "17"
                url = "https://x-access-token:ghp_secrettoken@github.com/acme/cassandra.git"
                branch = "my-feature"
            }.execute()

        val pushed = pushedByHost.map { it.second }
        assertThat(pushed).isNotEmpty
        // the node only ever reads java/python from this file, so url/branch have no business
        // being persisted there — least of all one carrying a token
        assertThat(pushed).allSatisfy { yaml ->
            assertThat(yaml).doesNotContain("ghp_secrettoken")
            assertThat(yaml).doesNotContain("url:")
            assertThat(yaml).doesNotContain("branch:")
        }
        val entry = CassandraVersion.loadFromFile(writeToTemp(pushed.first())).single { it.version == "fork" }
        assertThat(entry.java).isEqualTo("17")
        assertThat(entry.url).isNull()
        assertThat(entry.branch).isNull()
    }

    @Test
    fun `execute refreshes a declaration whose parameters no longer match`() {
        // The baked entry says java 11; the operator is installing with --java 21. Leaving the
        // node's declaration stale would make a later `cassandra use` pick the wrong JDK.
        nodeVersionsFile["db0"] = INSTALLED_VERSIONS_YAML + DECLARED_ENTRY_YAML
        nodeVersionsFile["db1"] = INSTALLED_VERSIONS_YAML + DECLARED_ENTRY_YAML
        val pushedByHost = capturePushes()

        flagDrivenInstall().apply { javaVersion = "21" }.execute()

        val written = CassandraVersion.loadFromFile(writeToTemp(pushedByHost.first().second))
        assertThat(written.single { it.version == "5.1-test" }.java).isEqualTo("21")
        // replaced in place, not appended a second time
        assertThat(written.map { it.version }).containsExactly("5.0", "5.1-test")
    }

    @Test
    fun `execute pushes nothing when the node's declaration already matches`() {
        nodeVersionsFile["db0"] = INSTALLED_VERSIONS_YAML + MATCHING_ENTRY_YAML
        nodeVersionsFile["db1"] = INSTALLED_VERSIONS_YAML + MATCHING_ENTRY_YAML

        flagDrivenInstall().execute()

        verify(mockRemoteOps, never()).upload(any(), any(), any())
        assertThat(executedCommands().filter { it.contains("install-cassandra-version") }).hasSize(2)
    }

    /** Records every uploaded yaml as (host alias, file contents). */
    private fun capturePushes(): List<Pair<String, String>> {
        // CassandraInstall visits hosts in parallel, so this collects from several threads.
        val pushed = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        doAnswer { invocation ->
            val host = invocation.getArgument<Host>(0)
            pushed.add(host.alias to Files.readString(invocation.getArgument<Path>(1)))
        }.whenever(mockRemoteOps).upload(any(), any(), any())
        return pushed
    }

    private fun executedCommands(): List<String> {
        val captor = argumentCaptor<String>()
        verify(mockRemoteOps, atLeast(0)).executeRemotely(any(), captor.capture(), any(), any())
        return captor.allValues
    }

    /** Host aliases, positionally aligned with [executedCommands]. */
    private fun executedHosts(): List<String> {
        val captor = argumentCaptor<Host>()
        verify(mockRemoteOps, atLeast(0)).executeRemotely(captor.capture(), any(), any(), any())
        return captor.allValues.map { it.alias }
    }

    private fun writeToTemp(content: String): Path {
        val path = Files.createTempFile("pushed", ".yaml")
        Files.writeString(path, content)
        path.toFile().deleteOnExit()
        return path
    }

    companion object {
        private val INSTALLED_VERSIONS_YAML =
            """
            - version: "5.0"
              java: "11"
              python: "3.11.9"

            """.trimIndent()

        private val DECLARED_ENTRY_YAML =
            """
            - version: "5.1-test"
              java: "11"
              python: "3.12.0"
              lazy: true

            """.trimIndent()

        /** Exactly what a flag-driven install of 5.1-test resolves to, so nothing needs pushing. */
        private val MATCHING_ENTRY_YAML =
            """
            - version: "5.1-test"
              java: "11"
              python: "3.11.9"
              ant_flags: "-Duse.jdk11=true"

            """.trimIndent()

        /**
         * Same as [MATCHING_ENTRY_YAML] on every field `declarationDifferences` compares
         * (java/python/antFlags), but with a real `url:` on disk — exactly what a baked HEAD-tracking
         * entry (5.0-HEAD, trunk, ...) looks like after the AMI bake. `nodeDeclaration` always strips
         * url/branch to null before comparing, so this must not be reported as a mismatch.
         */
        private val MATCHING_ENTRY_WITH_URL_YAML =
            """
            - version: "5.1-test"
              java: "11"
              python: "3.11.9"
              ant_flags: "-Duse.jdk11=true"
              url: "https://example.com/apache-cassandra-5.1-test-bin.tar.gz"

            """.trimIndent()
    }
}
