package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration

private val log = KotlinLogging.logger {}

private val json = Json { ignoreUnknownKeys = true }

/**
 * State of the Tailscale client on the machine running easy-db-lab.
 *
 * This is deliberately about the *local* client, not the cluster's control node:
 * [TailscaleService] answers "did the control node join the tailnet", which is a different
 * question and can be true while the operator's own machine is logged out.
 */
sealed interface LocalTailscaleState {
    /** The local client is logged in and routing traffic. */
    data object Connected : LocalTailscaleState

    /** The `tailscale` binary is not on this machine's PATH. */
    data object NotInstalled : LocalTailscaleState

    /**
     * The binary is present but the client is not routing.
     *
     * @param backendState Tailscale's own word for the state (`Stopped`, `NeedsLogin`, `NoState`,
     *   …), or [Constants.Tailscale.BACKEND_STATE_UNKNOWN] when the CLI answered with something
     *   we could not parse — typically because `tailscaled` itself is not running.
     */
    data class Disconnected(
        val backendState: String,
    ) : LocalTailscaleState
}

/**
 * Reports whether the Tailscale client on this machine can carry traffic.
 *
 * A cluster provisioned with Tailscale routes every connection — the Kubernetes API included —
 * over the tailnet, and starts no SOCKS tunnel. An operator whose own machine is logged out
 * therefore has no route to the cluster at all, so `up` checks this before it spends money on
 * EC2 instances.
 */
fun interface LocalTailscaleClient {
    /** Inspects the local client. Never throws: every fault maps to a [LocalTailscaleState]. */
    fun state(): LocalTailscaleState
}

/**
 * Default [LocalTailscaleClient]: reads `tailscale status --json --peers=false` and classifies
 * the reported `BackendState`.
 *
 * `--json` is used rather than the exit code of a bare `tailscale status` because it names the
 * state, and the operator-facing message is worth more when it can say *which* state the client
 * is in.
 */
class DefaultLocalTailscaleClient(
    private val runner: LocalCliRunner = DefaultLocalCliRunner,
    private val timeout: Duration = Duration.ofSeconds(Constants.Tailscale.LOCAL_STATUS_TIMEOUT_SECONDS),
) : LocalTailscaleClient {
    override fun state(): LocalTailscaleState =
        when (val result = runner.run(STATUS_COMMAND, timeout)) {
            is LocalCliResult.BinaryNotFound -> LocalTailscaleState.NotInstalled
            is LocalCliResult.TimedOut -> LocalTailscaleState.Disconnected(Constants.Tailscale.BACKEND_STATE_TIMED_OUT)
            is LocalCliResult.Completed -> classify(result)
        }

    private fun classify(result: LocalCliResult.Completed): LocalTailscaleState {
        val backendState = parseBackendState(result)
        return if (backendState == Constants.Tailscale.BACKEND_STATE_RUNNING) {
            LocalTailscaleState.Connected
        } else {
            LocalTailscaleState.Disconnected(backendState)
        }
    }

    /**
     * Reads `BackendState` out of the CLI's JSON, falling back to
     * [Constants.Tailscale.BACKEND_STATE_UNKNOWN]. A non-zero exit with no parseable JSON is the
     * shape of `tailscaled` itself being down, which is still a disconnected client.
     */
    private fun parseBackendState(result: LocalCliResult.Completed): String {
        if (result.stdout.isBlank()) {
            log.debug { "tailscale status exited ${result.exitCode} with no output" }
            return Constants.Tailscale.BACKEND_STATE_UNKNOWN
        }
        return try {
            json.decodeFromString<TailscaleStatusJson>(result.stdout).backendState
        } catch (e: SerializationException) {
            log.debug(e) { "Could not parse tailscale status JSON (exit ${result.exitCode})" }
            Constants.Tailscale.BACKEND_STATE_UNKNOWN
        }
    }

    private companion object {
        /** `--peers=false` keeps the output small and independent of the tailnet's size. */
        val STATUS_COMMAND = listOf("tailscale", "status", "--json", "--peers=false")
    }
}

/**
 * The one field of `tailscale status --json` this tool reads. The default covers a well-formed
 * response that omits the field entirely, which is indistinguishable from an unreadable one.
 */
@Serializable
private data class TailscaleStatusJson(
    @SerialName("BackendState")
    val backendState: String = Constants.Tailscale.BACKEND_STATE_UNKNOWN,
)
