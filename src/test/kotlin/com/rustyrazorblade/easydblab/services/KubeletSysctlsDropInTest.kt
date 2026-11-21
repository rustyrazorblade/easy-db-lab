package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.Yaml
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

/**
 * The kubelet allowlist drop-in (pod-network-tuning, design D3): it names every injected key, and
 * both the server and the agent install it before their start script runs.
 */
class KubeletSysctlsDropInTest {
    private val host = Host(public = "54.0.0.1", private = "10.0.1.5", alias = "control0", availabilityZone = "us-west-2a")

    /** A remote that records every upload's content by its staged name, and every command in order. */
    private class RecordingRemote {
        val remote: RemoteOperationsService = mock()
        val uploads = mutableMapOf<String, String>()

        init {
            doAnswer { call ->
                uploads[call.getArgument(2)] = Files.readString(call.getArgument<Path>(1))
            }.whenever(remote).upload(any(), any(), any(), any())
            whenever(remote.executeRemotely(any(), any(), any(), any())).thenReturn(Response(text = "", stderr = ""))
        }

        fun commands(host: Host): List<String> {
            val captor = argumentCaptor<String>()
            verify(remote, atLeastOnce()).executeRemotely(eq(host), captor.capture(), any(), any())
            return captor.allValues
        }

        /** The content of the file a `sudo mv` put at [target]. */
        fun installedAt(
            host: Host,
            target: String,
        ): String? {
            val move = commands(host).firstOrNull { it.contains(" $target ") } ?: return null
            return uploads.entries.firstOrNull { move.contains(it.key) }?.value
        }
    }

    @Test
    fun `the drop-in adds one allowlist arg naming every injected key`() {
        val parsed = Yaml.default.decodeFromString(KubeletSysctlsConfig.serializer(), KubeletSysctlsDropIn.render())

        val arg = parsed.kubeletArgs.single()
        assertThat(arg).startsWith("allowed-unsafe-sysctls=")
        assertThat(arg.substringAfter("=").split(",")).containsExactlyElementsOf(Constants.NodeTuning.POD_NETWORK_SYSCTLS)
        assertThat(KubeletSysctlsDropIn.render()).contains("kubelet-arg+").doesNotContain("ip_local")
    }

    @Test
    fun `the server installs the drop-in before it runs its start script`() {
        val recording = RecordingRemote()

        val result = DefaultK3sService(recording.remote, mock()).startServer(host, useCustomCni = true)

        assertThat(result.exceptionOrNull()).isNull()
        assertInstalledBeforeStart(recording, "start-k3s-server.sh --")
    }

    @Test
    fun `the agent installs the drop-in before it runs its start script`() {
        val recording = RecordingRemote()

        val result = DefaultK3sAgentService(recording.remote, mock()).start(host, AgentStartMode.START)

        assertThat(result.exceptionOrNull()).isNull()
        assertInstalledBeforeStart(recording, "start-k3s-agent.sh start")
    }

    private fun assertInstalledBeforeStart(
        recording: RecordingRemote,
        startCommand: String,
    ) {
        val target = Constants.NodeTuning.KUBELET_SYSCTLS_DROP_IN
        assertThat(recording.installedAt(host, target)).isEqualTo(KubeletSysctlsDropIn.render())
        val commands = recording.commands(host)
        val installed = commands.indexOfFirst { it.contains(" $target ") }
        val started = commands.indexOfFirst { it.contains(startCommand) }
        assertThat(started).describedAs(commands.joinToString("\n")).isGreaterThan(installed)
    }
}
