package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.exceptions.CommandExecutionException
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.shellQuote
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Installs one additional Cassandra version onto an already-running cluster, without an AMI rebuild.
 *
 * The version's install parameters come from a declared `cassandra_versions.yaml` entry (the same
 * merged candidate set an AMI bake resolves), or entirely from CLI flags for a one-off test. The
 * resolved entry is pushed into each targeted node's `/etc/cassandra_versions.yaml` — so a later
 * `cassandra use` finds the java/python it needs — and then `install-cassandra-version` runs
 * remotely, the same script the AMI bake uses.
 */
@RequireProfileSetup
@Command(
    name = "install",
    description = ["Install a Cassandra version onto a running cluster"],
)
class CassandraInstall : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()

    @Parameters(description = ["Cassandra version to install"], index = "0")
    lateinit var version: String

    @Mixin
    var hosts = HostsMixin()

    @Option(
        names = ["--url"],
        description = ["Tarball URL (.tar.gz) or git repository URL (with --branch)"],
    )
    var url: String = ""

    @Option(
        names = ["--branch"],
        description = ["Git branch to clone and build, requires --url"],
    )
    var branch: String = ""

    @Option(
        names = ["--java", "-j"],
        description = ["Java version to build and run this version with, e.g. 8, 11, 17, 21"],
    )
    var javaVersion: String = ""

    @Option(
        names = ["--python"],
        description = ["Python version cqlsh runs under (default: ${Constants.Cassandra.DEFAULT_PYTHON_VERSION})"],
    )
    var pythonVersion: String = ""

    @Option(
        names = ["--ant-flags"],
        description = ["Extra flags passed to ant when building from a branch"],
    )
    var antFlags: String = ""

    /** Per-host outcome, reported after every host has been attempted. */
    private sealed interface Outcome {
        data object Installed : Outcome

        data object AlreadyPresent : Outcome

        /**
         * The version is on disk, but with parameters other than the ones just resolved.
         *
         * @param differences one entry per field, e.g. "java: declared 11, requested 17"
         */
        data class DeclarationMismatch(
            val differences: List<String>,
        ) : Outcome
    }

    override fun execute() {
        check(version.isNotBlank()) { "A version to install is required, e.g. 'cassandra install 5.0'" }

        val resolved = resolveVersion(declaredCassandraVersions(context))
        val state = clusterState
        val available = state.getHosts(ServerType.Cassandra)
        val targeted = hostOperationsService.filteredHosts(state.hosts, ServerType.Cassandra, hosts.hostList)
        // Installing on nothing is never what was meant; without this the command reports success
        // for an install that never ran.
        require(targeted.isNotEmpty()) {
            if (available.isEmpty()) {
                "This cluster has no Cassandra nodes. Check you are in the right cluster workspace directory."
            } else {
                "--hosts ${hosts.hostList} matched no Cassandra nodes; available: " +
                    available.joinToString(", ") { it.alias }
            }
        }

        eventBus.emit(Event.Cassandra.InstallingVersion(resolved.version, targeted.size, hosts.hostList))

        val results =
            hostOperationsService.collectFromHosts(
                state.hosts,
                ServerType.Cassandra,
                hosts.hostList,
                parallel = true,
            ) { host -> installOnHost(host, resolved) }

        results.forEach { (host, result) ->
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is Outcome.Installed -> eventBus.emit(Event.Cassandra.VersionInstalled(host.alias, resolved.version))
                        is Outcome.AlreadyPresent ->
                            eventBus.emit(Event.Cassandra.VersionAlreadyInstalled(host.alias, resolved.version))
                        is Outcome.DeclarationMismatch ->
                            eventBus.emit(
                                Event.Cassandra.VersionDeclarationMismatch(
                                    host.alias,
                                    resolved.version,
                                    outcome.differences,
                                ),
                            )
                    }
                },
                onFailure = { failure ->
                    eventBus.emit(
                        Event.Cassandra.VersionInstallFailed(
                            host.alias,
                            resolved.version,
                            // a resolved git url can carry a token, and this event reaches
                            // MCP and Redis subscribers
                            redactUrlCredentials(failure.message ?: failure::class.simpleName ?: "unknown error"),
                        ),
                    )
                },
            )
        }

        val failed = results.filter { it.result.isFailure }.map { it.host.alias }
        val mismatched = results.filter { it.result.getOrNull() is Outcome.DeclarationMismatch }.map { it.host.alias }
        val problems =
            buildList {
                if (failed.isNotEmpty()) {
                    add("Failed to install Cassandra ${resolved.version} on: ${failed.joinToString(", ")}")
                }
                if (mismatched.isNotEmpty()) {
                    add(
                        "Cassandra ${resolved.version} is already installed with different parameters on: " +
                            "${mismatched.joinToString(", ")} — nothing was changed there",
                    )
                }
            }
        if (problems.isNotEmpty()) {
            throw CommandExecutionException(problems.joinToString("\n"))
        }
    }

    /**
     * Resolves the version's install parameters, CLI flags taking precedence over the declared
     * entry field by field.
     *
     * @param declared The merged candidate set from cassandra_versions.yaml plus profile extras
     */
    internal fun resolveVersion(declared: List<CassandraVersion>): CassandraVersion {
        val entry = declared.firstOrNull { it.version == version }

        val resolvedJava = javaVersion.ifBlank { entry?.java.orEmpty() }
        require(resolvedJava.isNotBlank()) {
            "No java version for $version: it is not declared in cassandra_versions.yaml, so --java is required."
        }

        val resolvedUrl = url.ifBlank { entry?.url.orEmpty() }
        val resolvedBranch = branch.ifBlank { entry?.branch.orEmpty() }
        require(resolvedBranch.isBlank() || resolvedUrl.isNotBlank()) {
            "Building $version from a branch requires a git repository: pass --url."
        }

        return CassandraVersion(
            version = version,
            java = resolvedJava,
            python =
                pythonVersion.ifBlank {
                    entry?.python.orEmpty().ifBlank { Constants.Cassandra.DEFAULT_PYTHON_VERSION }
                },
            jvmOptions = entry?.jvmOptions,
            antFlags = antFlags.ifBlank { entry?.antFlags.orEmpty() }.ifBlank { null },
            url = resolvedUrl.ifBlank { null },
            branch = resolvedBranch.ifBlank { null },
        )
    }

    /**
     * Declares the version on the node, then runs the install script.
     *
     * Whether the version is installed is decided by what is on disk, never by the node's version
     * list: every AMI ships the whole cassandra_versions.yaml, `lazy: true` entries included, so a
     * lazy version is always already declared on a node that has never installed it.
     *
     * The declaration is refreshed whenever it differs from what was resolved — a baked entry
     * saying `java: 11` must not survive an install run with `--java 21`, or a later
     * `cassandra use` picks the wrong JDK. It is never rolled back on failure: declared but not
     * installed is the harmless state (it is exactly what a lazy entry looks like), whereas
     * installed but not declared cannot be repaired, because every retry short-circuits on the
     * disk check before it can re-declare.
     */
    private fun installOnHost(
        clusterHost: ClusterHost,
        resolved: CassandraVersion,
    ): Outcome {
        val host = clusterHost.toHost()
        val alreadyInstalled = isInstalled(host, resolved.version)

        val localFile = File.createTempFile("cassandra_versions-${clusterHost.alias}", ".yaml")
        try {
            remoteOps.download(host, REMOTE_VERSIONS_FILE, localFile.toPath())
            val existing = CassandraVersion.loadFromFile(localFile.toPath())
            val declaration = nodeDeclaration(resolved)

            if (alreadyInstalled) {
                val declared = existing.firstOrNull { it.version == declaration.version }
                val differences = declarationDifferences(declared, declaration)
                return when {
                    // On disk but undeclared — `cassandra use` hard-exits without java/python, and
                    // there is no existing declaration for these flags to contradict. Repair it.
                    declared == null -> {
                        pushVersions(clusterHost, existing + declaration, localFile)
                        Outcome.AlreadyPresent
                    }
                    // What is on disk was built with the parameters the node declares, so rewriting
                    // the declaration to today's flags would make it describe something that was
                    // never installed. Say so instead of quietly dropping the flags. Compared only
                    // on java/python/antFlags — url/branch are always null in a declaration (see
                    // nodeDeclaration), so a baked HEAD entry's real url/branch must not trip this.
                    differences.isNotEmpty() -> Outcome.DeclarationMismatch(differences)
                    else -> Outcome.AlreadyPresent
                }
            }

            val matchesExisting =
                existing.any {
                    it.version == declaration.version && declarationDifferences(it, declaration).isEmpty()
                }
            if (!matchesExisting) {
                pushVersions(clusterHost, existing.replacingOrAdding(declaration), localFile)
            }

            remoteOps.executeRemotely(host, installCommand(resolved)).text
            return Outcome.Installed
        } finally {
            localFile.delete()
        }
    }

    /**
     * How the node's own declaration of a version differs from what was just resolved, one entry
     * per field, empty when they agree.
     */
    private fun declarationDifferences(
        declared: CassandraVersion?,
        requested: CassandraVersion,
    ): List<String> {
        if (declared == null) {
            return listOf("this node does not declare ${requested.version} at all")
        }
        return listOfNotNull(
            difference("java", declared.java, requested.java),
            difference("python", declared.python, requested.python),
            difference("ant flags", declared.antFlags.orEmpty(), requested.antFlags.orEmpty()),
        )
    }

    private fun difference(
        field: String,
        declared: String,
        requested: String,
    ): String? = "$field: declared $declared, requested $requested".takeIf { declared != requested }

    /**
     * What the node's version list should say about this version.
     *
     * `use-cassandra` reads only `java`/`python` from that file — `url`/`branch` have no remote
     * consumer, and a git URL can carry a token, so persisting them would leave a credential
     * sitting in `/etc/cassandra_versions.yaml` indefinitely.
     */
    private fun nodeDeclaration(resolved: CassandraVersion): CassandraVersion = resolved.copy(url = null, branch = null)

    private fun List<CassandraVersion>.replacingOrAdding(entry: CassandraVersion): List<CassandraVersion> =
        if (any { it.version == entry.version }) {
            map { if (it.version == entry.version) entry else it }
        } else {
            this + entry
        }

    /**
     * Whether the version is on disk. `install-cassandra-version` makes the same check itself, but
     * asking first is what lets the command report an untouched host rather than shelling out.
     */
    private fun isInstalled(
        host: Host,
        version: String,
    ): Boolean {
        val path = "$CASSANDRA_INSTALL_DIR/$version".shellQuote()
        val check = "test -d $path && echo $INSTALLED_MARKER || true"
        return remoteOps.executeRemotely(host, check, output = false).text.trim() == INSTALLED_MARKER
    }

    private fun pushVersions(
        clusterHost: ClusterHost,
        versions: List<CassandraVersion>,
        localFile: File,
    ) {
        val host = clusterHost.toHost()
        CassandraVersion.write(versions, localFile)
        remoteOps.upload(host, localFile.toPath(), STAGED_VERSIONS_FILE)
        remoteOps.executeRemotely(host, "sudo mv $STAGED_VERSIONS_FILE $REMOTE_VERSIONS_FILE").text
    }

    private fun installCommand(resolved: CassandraVersion): String =
        buildString {
            append("install-cassandra-version ")
            append(resolved.version.shellQuote())
            resolved.url?.let { append(" --url ${it.shellQuote()}") }
            resolved.branch?.let { append(" --branch ${it.shellQuote()}") }
            append(" --java ${resolved.java.shellQuote()}")
            resolved.antFlags?.let { append(" --ant-flags ${it.shellQuote()}") }
        }

    companion object {
        private const val REMOTE_VERSIONS_FILE = "/etc/cassandra_versions.yaml"
        private const val STAGED_VERSIONS_FILE = "cassandra_versions.yaml"
        private const val CASSANDRA_INSTALL_DIR = "/usr/local/cassandra"
        private const val INSTALLED_MARKER = "installed"
    }
}
