package com.rustyrazorblade.easydblab.proxy

import java.time.Duration

/** `ssh -v` dialing a host itself: `Connecting to <name> [<address>] port <port>.` */
private val SSH_DIALING = Regex("""^debug1: Connecting to \S+ \[([^\]]+)] port (\d+)\.""")

/** `ssh -v` starting a `ProxyCommand` (the `ssm` transport) instead of dialing. */
private val SSH_PROXY_COMMAND = Regex("""^debug1: Executing proxy command:""")

/**
 * `ssh -v` past the connect: the TCP connection is up, or the server's version came back through a
 * `ProxyCommand` (which never prints `Connection established`).
 */
private val SSH_CONNECTED = Regex("""^debug1: (Connection established\.|Remote protocol version )""")

/** [waited] in seconds, rounded up, so a part of a second is never reported as no wait. */
private fun wholeSeconds(waited: Duration): Long = if (waited.toNanosPart() == 0) waited.toSeconds() else waited.toSeconds() + 1

/**
 * How far one `ssh -v` run got toward its server, read from its transcript.
 *
 * A tunnel whose ssh is still alive when the verify window ends gives no error line of its own: ssh
 * is still waiting in `connect()`, and only its `debug1:` lines say so. This names that stage, so the
 * failure message can say ssh never connected instead of showing nothing.
 */
internal sealed interface SshConnectStage {
    /**
     * The sentence that says ssh never connected to [alias] in [waited]; null when this stage makes
     * no such claim.
     */
    fun failureSentence(
        alias: String,
        waited: Duration,
    ): String? = null

    /** The transcript shows no connection attempt. */
    data object NotStarted : SshConnectStage

    /** ssh dialed [address] on [port] and never logged that the connection was established. */
    data class Connecting(
        val address: String,
        val port: Int,
    ) : SshConnectStage {
        override fun failureSentence(
            alias: String,
            waited: Duration,
        ): String = "ssh could not connect to $alias ($address) port $port within ${wholeSeconds(waited)}s."
    }

    /** ssh started its `ProxyCommand` and the server never answered through it. */
    data object ConnectingThroughProxyCommand : SshConnectStage {
        override fun failureSentence(
            alias: String,
            waited: Duration,
        ): String = "ssh could not connect to $alias through its ProxyCommand within ${wholeSeconds(waited)}s."
    }

    /** ssh reached the server; whatever failed came after the connect. */
    data object Connected : SshConnectStage

    companion object {
        /** The stage the last connect-related line of [transcript] shows. */
        fun of(transcript: List<String>): SshConnectStage =
            transcript.fold<String, SshConnectStage>(NotStarted) { stage, line ->
                val dialing = SSH_DIALING.find(line)
                when {
                    dialing != null -> Connecting(dialing.groupValues[1], dialing.groupValues[2].toInt())
                    SSH_PROXY_COMMAND.containsMatchIn(line) -> ConnectingThroughProxyCommand
                    SSH_CONNECTED.containsMatchIn(line) -> Connected
                    else -> stage
                }
            }
    }
}
