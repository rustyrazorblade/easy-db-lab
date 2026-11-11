package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [DefaultLocalTailscaleClient].
 *
 * The distinction these cover is the one the operator-facing message rests on: a machine without
 * Tailscale installed is a different fault, with a different remedy, from a machine that has it
 * and is logged out. Everything else — a wedged daemon, unreadable output — has to land on the
 * "not connected" side rather than being mistaken for a healthy client.
 */
class LocalTailscaleClientTest {
    private val commands = mutableListOf<List<String>>()

    private fun clientReturning(result: LocalCliResult): DefaultLocalTailscaleClient =
        DefaultLocalTailscaleClient(
            runner = { command, _ ->
                commands.add(command)
                result
            },
            timeout = Duration.ofSeconds(1),
        )

    private fun completed(
        stdout: String,
        exitCode: Int = 0,
    ) = LocalCliResult.Completed(exitCode = exitCode, stdout = stdout)

    @Test
    fun `reports connected when the CLI reports a Running backend`() {
        val client = clientReturning(completed("""{"BackendState":"Running","Self":{"HostName":"laptop"}}"""))

        assertThat(client.state()).isEqualTo(LocalTailscaleState.Connected)
    }

    @Test
    fun `reports disconnected and carries Tailscale's own word for the state`() {
        val client = clientReturning(completed("""{"BackendState":"NeedsLogin"}""", exitCode = 1))

        assertThat(client.state()).isEqualTo(LocalTailscaleState.Disconnected("NeedsLogin"))
    }

    @Test
    fun `reports not installed when the binary is absent, never merely disconnected`() {
        val client = clientReturning(LocalCliResult.BinaryNotFound)

        assertThat(client.state()).isEqualTo(LocalTailscaleState.NotInstalled)
    }

    @Test
    fun `treats an unreadable status as disconnected rather than connected`() {
        val client = clientReturning(completed("failed to connect to local tailscaled", exitCode = 1))

        assertThat(client.state()).isEqualTo(LocalTailscaleState.Disconnected(Constants.Tailscale.BACKEND_STATE_UNKNOWN))
    }

    @Test
    fun `treats empty output as disconnected`() {
        val client = clientReturning(completed(""))

        assertThat(client.state()).isEqualTo(LocalTailscaleState.Disconnected(Constants.Tailscale.BACKEND_STATE_UNKNOWN))
    }

    @Test
    fun `treats a hung CLI as disconnected`() {
        val client = clientReturning(LocalCliResult.TimedOut)

        assertThat(client.state()).isEqualTo(LocalTailscaleState.Disconnected(Constants.Tailscale.BACKEND_STATE_TIMED_OUT))
    }

    @Test
    fun `asks for JSON without the peer list, so the output stays small on a large tailnet`() {
        clientReturning(completed("""{"BackendState":"Running"}""")).state()

        assertThat(commands).singleElement().isEqualTo(listOf("tailscale", "status", "--json", "--peers=false"))
    }
}
