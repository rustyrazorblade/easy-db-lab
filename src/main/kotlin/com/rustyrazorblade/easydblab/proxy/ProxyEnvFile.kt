package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.writeTextAtomically
import java.io.File

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
 * @property tailscaleActive whether the cluster is reached over Tailscale; null before `up` recorded it
 * @property tunnel the verified tunnel; null while no tunnel is recorded
 */
data class ProxyEnv(
    val tailscaleActive: Boolean?,
    val tunnel: RecordedTunnel?,
)

/**
 * The workspace's `.socks5-proxy.env`, the only source of proxy state for shell-side tools.
 *
 * The tool wrappers in `<workspace>/bin/`, the tunnel script and `env.sh` source this file, so shell
 * code never reads `.socks5-proxy-state` or parses JSON. Any value a shell-side tool needs belongs
 * here. This class is the file's only writer. It holds only [Constants.Proxy.ENV_TAILSCALE_ACTIVE],
 * [Constants.Proxy.ENV_SOCKS_PORT] and [Constants.Proxy.ENV_SOCKS_PID]; the last two are written and
 * removed together. Each update keeps the other keys and replaces the file in one rename, so a
 * wrapper that sources it while another CLI process writes always reads a whole file.
 *
 * @param workspace the cluster workspace directory
 */
class ProxyEnvFile(
    workspace: File,
) {
    /** The env file itself. */
    val file: File = File(workspace, Constants.Proxy.ENV_FILE)

    /** The recorded state; both fields are null when the file does not exist. */
    fun read(): ProxyEnv {
        val values = readValues()
        val port = values[Constants.Proxy.ENV_SOCKS_PORT]?.toIntOrNull()
        val pid = values[Constants.Proxy.ENV_SOCKS_PID]?.toLongOrNull()
        return ProxyEnv(
            tailscaleActive = values[Constants.Proxy.ENV_TAILSCALE_ACTIVE]?.toBooleanStrictOrNull(),
            tunnel = if (port != null && pid != null) RecordedTunnel(port, pid) else null,
        )
    }

    /** Records whether the cluster is reached over Tailscale, keeping any recorded tunnel. */
    fun recordTailscale(active: Boolean) = write(read().copy(tailscaleActive = active))

    /** Records the verified tunnel on [port] whose script is [pid], keeping the Tailscale flag. */
    fun recordTunnel(
        port: Int,
        pid: Long,
    ) = write(read().copy(tunnel = RecordedTunnel(port, pid)))

    /** Removes the recorded tunnel's port and PID, keeping the Tailscale flag. Writes nothing when no file exists. */
    fun removeTunnel() {
        if (file.exists()) write(read().copy(tunnel = null))
    }

    /** Deletes the file. */
    fun delete() {
        file.delete()
    }

    private fun write(env: ProxyEnv) {
        val lines =
            listOfNotNull(
                env.tailscaleActive?.let { "${Constants.Proxy.ENV_TAILSCALE_ACTIVE}=$it" },
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
}
