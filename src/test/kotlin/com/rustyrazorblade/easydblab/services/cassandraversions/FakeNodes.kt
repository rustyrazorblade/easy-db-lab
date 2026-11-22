package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.ssh.Response
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A fleet of db nodes for the install tests: each alias has the versions under its
 * `/usr/local/cassandra`, and `install-cassandra-version` adds to them, so a later listing sees what
 * an install did. Hand-written rather than a Mockito mock because the disk is state that the code
 * under test both reads and changes.
 */
class FakeNodes : RemoteOperationsService {
    /** The versions on each node's disk. */
    val disks = ConcurrentHashMap<String, MutableSet<String>>()

    /** Aliases whose install script exits non-zero, with its stderr. */
    val installFailures = ConcurrentHashMap<String, String>()

    /** Per alias, a command prefix that fails there, with its stderr. */
    private val failures = ConcurrentHashMap<String, Pair<String, String>>()

    /** Makes a command starting with [prefix] fail on [alias] with [stderr]. */
    fun failOn(
        alias: String,
        prefix: String,
        stderr: String,
    ) {
        failures[alias] = prefix to stderr
    }

    /** Per alias, the stdout of each command prefix: alias to (prefix to stdout). */
    private val responses = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** Makes a command starting with [prefix] print [stdout] on [alias]. */
    fun respond(
        alias: String,
        prefix: String,
        stdout: String,
    ) {
        responses.getOrPut(alias) { ConcurrentHashMap() }[prefix] = stdout
    }

    /** Aliases whose listing fails. */
    val unreachable = ConcurrentHashMap.newKeySet<String>()

    /** Every command run, with the alias and whether it was sent as a secret. */
    val commands: MutableList<Command> = Collections.synchronizedList(mutableListOf())

    /** One remote command. */
    data class Command(
        val alias: String,
        val text: String,
        val secret: Boolean,
    )

    fun disk(alias: String): MutableSet<String> = disks.getOrPut(alias) { ConcurrentHashMap.newKeySet() }

    fun commandsOn(alias: String): List<String> = synchronized(commands) { commands.filter { it.alias == alias }.map { it.text } }

    fun scriptRuns(alias: String): List<String> = commandsOn(alias).filter { it.startsWith("install-cassandra-version") }

    override fun executeRemotely(
        host: Host,
        command: String,
        output: Boolean,
        secret: Boolean,
    ): Response {
        commands += Command(host.alias, command, secret)
        failures[host.alias]?.takeIf { (prefix, _) -> command.startsWith(prefix) }?.let { (_, stderr) -> throw failure(command, stderr) }
        responses[host.alias]?.entries?.firstOrNull { command.startsWith(it.key) }?.let { return Response(it.value) }
        return when {
            command.startsWith("ls ") -> {
                if (host.alias in unreachable) throw failure(command, "ssh: connect to host ${host.alias}: timed out")
                Response((disk(host.alias) + "current").joinToString("\n"))
            }
            command.startsWith("install-cassandra-version") -> {
                installFailures[host.alias]?.let { throw failure(command, it) }
                disk(host.alias) += command.split(" ")[1].trim('\'')
                Response("")
            }
            else -> Response("")
        }
    }

    override fun executeRemotelyWithInput(
        host: Host,
        command: String,
        input: String,
        output: Boolean,
    ): Response = executeRemotely(host, command, output, secret = false)

    private fun failure(
        command: String,
        stderr: String,
    ) = RemoteCommandFailedException(command, "", stderr, "Remote command failed")

    override fun upload(
        host: Host,
        local: Path,
        remote: String,
        notice: String,
    ) = error("the install path uploads nothing")

    override fun uploadDirectory(
        host: Host,
        localDir: File,
        remoteDir: String,
    ) = error("unused")

    override fun uploadDirectory(
        host: Host,
        version: Version,
    ) = error("unused")

    override fun replaceDirectory(
        host: Host,
        localDir: File,
        remoteDir: String,
        owner: String,
    ) = error("unused")

    override fun download(
        host: Host,
        remote: String,
        local: Path,
    ) = error("the install path downloads nothing")

    override fun downloadDirectory(
        host: Host,
        remoteDir: String,
        localDir: File,
        includeFilters: List<String>,
        excludeFilters: List<String>,
    ) = error("unused")

    override fun getRemoteVersion(
        host: Host,
        inputVersion: String,
    ): Version = error("unused")

    companion object {
        fun host(alias: String) =
            ClusterHost(
                publicIp = "54.1.2.3",
                privateIp = "10.0.1.1",
                alias = alias,
                availabilityZone = "us-west-2a",
                instanceId = "i-$alias",
            )
    }
}

/** Cassandra on each node: running, not running, or [uncheckable] (the check fails). A fake because `isRunning` returns a Kotlin `Result`. */
class FakeCassandraService(
    private val running: Set<String> = emptySet(),
    private val uncheckable: Set<String> = emptySet(),
) : CassandraService {
    override fun start(
        host: Host,
        wait: Boolean,
    ): Result<Unit> = Result.success(Unit)

    override fun stop(host: Host): Result<Unit> = Result.success(Unit)

    override fun restart(host: Host): Result<Unit> = Result.success(Unit)

    override fun waitForUpNormal(host: Host): Result<Unit> = Result.success(Unit)

    override fun isRunning(host: Host): Result<Boolean> =
        when (host.alias) {
            in uncheckable -> Result.failure(IllegalStateException("systemctl timed out on ${host.alias}"))
            else -> Result.success(host.alias in running)
        }
}

/** Fixed version sources: the ASF releases [asf], and nothing in the other sources. */
class RecordOnlySources(
    private val asf: List<String> = listOf("4.1.7", "5.0.6"),
    private val stale: Boolean = false,
) : VersionSources {
    override val profileBuildDir = File("/profile/cassandra_versions")

    override fun profileBuild(name: String): ProfileBuild? = null

    override fun profileBuilds(): List<ProfileBuild> = emptyList()

    override fun asfReleases(refresh: Boolean): Cached<AsfReleases> = Cached(AsfReleases(asf), Instant.EPOCH, stale)

    override fun cassandraBuilds(refresh: Boolean): Cached<List<CassandraBuild>> = Cached(emptyList(), Instant.EPOCH, stale = false)

    override fun cassandraBuildsManifest(build: CassandraBuild): CassandraBuildsManifest? = null

    override fun catalogBuild(name: String): CatalogBuild? = null

    override fun catalogBuildNames(): List<String> = emptyList()

    override fun downloadCacheRoot(): ClusterS3Path = ClusterS3Path.downloadCacheRoot("acct-bucket")
}
