package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.writeTextAtomically
import java.io.File

/**
 * The proxy state recorded in a workspace's [ProxyEnvFile].
 *
 * @property tailscaleActive whether the cluster is reached over Tailscale; null before `up` recorded it
 * @property socksPort the verified tunnel's local port; null while no tunnel is recorded
 */
data class ProxyEnv(
    val tailscaleActive: Boolean?,
    val socksPort: Int?,
)

/**
 * The workspace's `.socks5-proxy.env`, the only source of proxy state for shell-side tools.
 *
 * The tool wrappers in `<workspace>/bin/` and `env.sh` source this file on every call, so shell code
 * never reads `.socks5-proxy-state` or parses JSON. Any value a shell-side tool needs belongs here.
 * This class is the file's only writer. It holds only [Constants.Proxy.ENV_TAILSCALE_ACTIVE] and
 * [Constants.Proxy.ENV_SOCKS_PORT]. Each update keeps the other key and replaces the file in one
 * rename, so a wrapper that sources it while another CLI process writes always reads a whole file.
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
        return ProxyEnv(
            tailscaleActive = values[Constants.Proxy.ENV_TAILSCALE_ACTIVE]?.toBooleanStrictOrNull(),
            socksPort = values[Constants.Proxy.ENV_SOCKS_PORT]?.toIntOrNull(),
        )
    }

    /** Records whether the cluster is reached over Tailscale, keeping any recorded port. */
    fun recordTailscale(active: Boolean) = write(read().copy(tailscaleActive = active))

    /** Records the verified tunnel's [port], keeping the Tailscale flag. */
    fun recordPort(port: Int) = write(read().copy(socksPort = port))

    /** Removes the recorded port, keeping the Tailscale flag. Writes nothing when no file exists. */
    fun removePort() {
        if (file.exists()) write(read().copy(socksPort = null))
    }

    /** Deletes the file. */
    fun delete() {
        file.delete()
    }

    private fun write(env: ProxyEnv) {
        val lines =
            listOfNotNull(
                env.tailscaleActive?.let { "${Constants.Proxy.ENV_TAILSCALE_ACTIVE}=$it" },
                env.socksPort?.let { "${Constants.Proxy.ENV_SOCKS_PORT}=$it" },
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
