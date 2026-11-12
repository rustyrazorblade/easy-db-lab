package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

/**
 * Prepares the control node for the compactor: the scratch directories, owned by the user each
 * compactor runs as, and the agent jar at its SHA-256 path. The commands run on the control node over
 * SSH, so [RemoteOperationsService] is mocked and the command strings are what is checked.
 */
class CompactorAgentInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val host = Host(public = "1.2.3.4", private = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")
    private val remoteOps = mock<RemoteOperationsService>()
    private val events = mutableListOf<Event>()
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    private val jar by lazy { CompactorAgentJarFile(Files.writeString(dir.resolve("compactor-lease.jar"), "agent")) }
    private val installer by lazy { CompactorAgentInstaller(remoteOps, eventBus) }
    private val remotePath by lazy { jar.remote.hostPath }

    private fun commands(): List<String> {
        val captor = argumentCaptor<String>()
        verify(remoteOps, org.mockito.kotlin.atLeastOnce()).executeRemotely(eq(host), captor.capture(), any(), any())
        return captor.allValues
    }

    private fun jarPresent(present: Boolean) {
        whenever(remoteOps.executeRemotely(eq(host), any(), any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(1)
            when {
                command.startsWith("test -f") -> Response(if (present) "present" else "absent")
                else -> Response("")
            }
        }
    }

    @Test
    fun `the scratch directories are created and given to the user each compactor runs as`() {
        jarPresent(true)

        installer.install(host, jar)

        val prepare = commands().first()
        assertThat(prepare).contains(
            "sudo mkdir -p /mnt/db1/compactor/mimir /mnt/db1/compactor/loki /mnt/db1/compactor/tempo /mnt/db1/compactor/agent",
            "sudo chown -R 0:0 /mnt/db1/compactor/mimir",
            "sudo chown -R 10001:10001 /mnt/db1/compactor/loki",
            "sudo chown -R 10001:10001 /mnt/db1/compactor/tempo",
        )
    }

    @Test
    fun `a jar not yet on the control node is uploaded beside its SHA-256 path and moved into place`() {
        jarPresent(false)

        val result = installer.install(host, jar)

        assertThat(result).isEqualTo(jar.remote)
        val staged = argumentCaptor<String>()
        verify(remoteOps).upload(eq(host), eq(jar.path), staged.capture())
        assertThat(commands()).anySatisfy { command ->
            assertThat(command).isEqualTo("sudo mv ${staged.firstValue} $remotePath && sudo chmod 0644 $remotePath")
        }
        assertThat(events).contains(Event.Grafana.CompactorAgentPrepared(remotePath, uploaded = true))
    }

    @Test
    fun `a jar already at its SHA-256 path is not uploaded again`() {
        jarPresent(true)

        installer.install(host, jar)

        verify(remoteOps, never()).upload(any(), any(), any())
        assertThat(commands()).anySatisfy { assertThat(it).startsWith("test -f $remotePath") }
        assertThat(events).contains(Event.Grafana.CompactorAgentPrepared(remotePath, uploaded = false))
    }
}
