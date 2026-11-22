package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.writeTextAtomically
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A verified tunnel as the env file records it: its local [port] and the [pid] of its
 * `edl-socks-tunnel` script. Shell-side tools use the port only while that PID is a live process.
 */
data class RecordedTunnel(
    val port: Int,
    val pid: Long,
)

/**
 * The proxy state recorded in a workspace's [ProxyEnvFile].
 *
 * @property networkMode how this machine reaches the cluster; null before the CLI recorded it, or
 *   when the file holds a value that is not a known mode
 * @property tunnel the verified tunnel; null while no tunnel is recorded
 */
data class ProxyEnv(
    val networkMode: NetworkMode?,
    val tunnel: RecordedTunnel?,
)

/**
 * The workspace's `.socks5-proxy.env`, the only source of proxy state for shell-side tools.
 *
 * The tool wrappers in `<workspace>/bin/`, the tunnel script and `env.sh` source this file, so shell
 * code never reads `.socks5-proxy-state` or parses JSON. Any value a shell-side tool needs belongs
 * here. This class is the file's only writer. It writes only [Constants.Proxy.ENV_NETWORK_MODE],
 * [Constants.Proxy.ENV_NAMESPACE], [Constants.Proxy.ENV_SOCKS_PORT] and [Constants.Proxy.ENV_SOCKS_PID];
 * the port and the PID are written and removed together. Each update keeps the other keys and replaces the file in one
 * rename, so a wrapper that sources it while another CLI process writes always reads a whole file.
 *
 * A file written by an earlier release holds [Constants.Proxy.ENV_TAILSCALE_ACTIVE] in place of the
 * mode. [read] takes it as [NetworkMode.Tailscale] or [NetworkMode.Socks]. Only [recordNetworkMode]
 * converts the file, replacing the old key with the new one; a tunnel write keeps the old key as it
 * found it, because only a mode write is preceded by the wrapper install ([WorkspaceShellTools]),
 * and an old wrapper knows only the old key.
 *
 * Each update reads the file, changes one key and writes it back while it holds an exclusive lock on
 * [Constants.Proxy.ENV_LOCK_FILE], so two CLI processes that update different keys at once keep both.
 * A JVM cannot take a second lock on a file it has locked already, so the threads of one process also
 * take turns on [localLock] first. Readers take no lock: the rename gives them a whole file.
 *
 * @param workspace the cluster workspace directory
 */
class ProxyEnvFile(
    workspace: File,
) {
    /** The env file itself. */
    val file: File = File(workspace, Constants.Proxy.ENV_FILE)

    /** The recorded state; with no file, both fields are null. */
    fun read(): ProxyEnv = parse(readValues()).env

    /**
     * The cluster namespace the `env.sh` helpers act in, or null before the CLI recorded it. Not part
     * of [ProxyEnv]: no proxy reader needs it, and the tool wrappers never read it (design D5).
     */
    fun namespace(): String? = parse(readValues()).namespace

    private val lockFile = File(workspace, Constants.Proxy.ENV_LOCK_FILE)

    /**
     * Whether the file already holds [Constants.Proxy.ENV_NETWORK_MODE], whatever its value: false
     * for no file and for a file an earlier release wrote, which the next mode write converts.
     */
    fun recordsNetworkMode(): Boolean = parse(readValues()).rawMode != null

    /**
     * Records [mode], keeping any recorded tunnel, and drops the legacy Tailscale key. Call it through
     * [WorkspaceShellTools.prepare], which installs the wrappers that read the new key first.
     */
    fun recordNetworkMode(mode: NetworkMode) = update { it.copy(env = it.env.copy(networkMode = mode), legacyTailscale = null) }

    /** Records the cluster namespace, keeping every other key as it found it. */
    fun recordNamespace(namespace: String) = update { it.copy(namespace = namespace) }

    /** Records the verified tunnel on [port] whose script is [pid], keeping the recorded mode in its format. */
    fun recordTunnel(
        port: Int,
        pid: Long,
    ) = update { it.copy(env = it.env.copy(tunnel = RecordedTunnel(port, pid))) }

    /** Removes the recorded tunnel's port and PID, keeping the recorded mode. Writes nothing when no file exists. */
    fun removeTunnel() {
        if (!file.exists()) return
        locked { if (file.exists()) write(parse(readValues()).let { it.copy(env = it.env.copy(tunnel = null)) }) }
    }

    /**
     * Replaces the recorded state with [change] applied to it, under the lock. A change that leaves
     * the file as it is writes nothing and takes no lock (every command may re-record the network mode).
     */
    private fun update(change: (StoredEnv) -> StoredEnv) {
        if (file.exists() && parse(readValues()).let { change(it) == it }) return
        locked {
            val current = parse(readValues())
            val changed = change(current)
            if (!file.exists() || changed != current) write(changed)
        }
    }

    /** Runs [block] while this thread holds [localLock] and this process holds the lock file. */
    private fun <T> locked(block: () -> T): T =
        localLock.withLock {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }

    /** Deletes the file. */
    fun delete() {
        file.delete()
    }

    private fun write(stored: StoredEnv) {
        val env = stored.env
        val lines =
            listOfNotNull(
                stored.modeLine?.let { "${Constants.Proxy.ENV_NETWORK_MODE}=$it" },
                stored.legacyTailscale?.let { "${Constants.Proxy.ENV_TAILSCALE_ACTIVE}=$it" },
                stored.namespace?.let { "${Constants.Proxy.ENV_NAMESPACE}=$it" },
                env.tunnel?.let { "${Constants.Proxy.ENV_SOCKS_PORT}=${it.port}" },
                env.tunnel?.let { "${Constants.Proxy.ENV_SOCKS_PID}=${it.pid}" },
            )
        file.writeTextAtomically(lines.joinToString(separator = "") { "$it\n" })
    }

    private fun readValues(): Map<String, String> =
        if (file.isFile) {
            file
                .readLines()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { (key, value) -> key.trim() to value.trim() }
        } else {
            emptyMap()
        }

    /**
     * The file as found, with what a rewrite must keep: the raw mode value (an unknown one is written
     * back unchanged by a tunnel write), the legacy Tailscale key of an unconverted file and the
     * recorded namespace.
     */
    private data class StoredEnv(
        val env: ProxyEnv,
        val rawMode: String?,
        val legacyTailscale: String?,
        val namespace: String?,
    ) {
        /**
         * The mode value to write: none while the file is unconverted (the legacy key stays instead),
         * else the recorded mode, else whatever unknown value the file held.
         */
        val modeLine: String? get() = if (legacyTailscale != null) null else env.networkMode?.id ?: rawMode
    }

    private fun parse(values: Map<String, String>): StoredEnv {
        val port = values[Constants.Proxy.ENV_SOCKS_PORT]?.toIntOrNull()
        val pid = values[Constants.Proxy.ENV_SOCKS_PID]?.toLongOrNull()
        val rawMode = values[Constants.Proxy.ENV_NETWORK_MODE]
        val legacy = values[Constants.Proxy.ENV_TAILSCALE_ACTIVE]
        val mode =
            when {
                rawMode != null -> NetworkMode.fromId(rawMode)
                legacy == null -> null
                legacy.toBooleanStrictOrNull() == true -> NetworkMode.Tailscale
                else -> NetworkMode.Socks
            }
        return StoredEnv(
            env = ProxyEnv(networkMode = mode, tunnel = if (port != null && pid != null) RecordedTunnel(port, pid) else null),
            rawMode = rawMode,
            legacyTailscale = legacy.takeIf { rawMode == null },
            namespace = values[Constants.Proxy.ENV_NAMESPACE],
        )
    }

    private companion object {
        /** Taken before the file lock, by every thread of this JVM, for every workspace. */
        val localLock = ReentrantLock()
    }
}
