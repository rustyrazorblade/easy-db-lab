package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File

/**
 * [SshClusterTooling] against a mocked [RemoteOperationsService], which CLAUDE.md allows for remote
 * CLI tools, and a real [ClusterStateManager] over a `@TempDir` state file, so a change of cluster
 * state is a real one.
 */
internal class SshClusterToolingTest {
    @TempDir
    lateinit var stateDir: File

    private val remoteOps: RemoteOperationsService = mock()
    private lateinit var stateManager: ClusterStateManager
    private lateinit var tooling: SshClusterTooling

    private val controlHost = clusterHost("54.1.2.3", "10.0.0.1")
    private val kubeconfig = "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}"

    private fun clusterHost(
        publicIp: String,
        privateIp: String,
    ) = ClusterHost(publicIp = publicIp, privateIp = privateIp, alias = "control0", availabilityZone = "us-west-2a")

    private fun saveState(control: ClusterHost) {
        stateManager.replace(
            ClusterState(name = "test", hosts = mapOf(ServerType.Control to listOf(control))),
        )
    }

    @BeforeEach
    fun setup() {
        stateManager = ClusterStateManager(File(stateDir, "state.json"))
        saveState(controlHost)
        tooling = SshClusterTooling(remoteOps, stateManager)
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response("out"))
        whenever(remoteOps.executeRemotelyWithInput(any(), any(), any(), any())).doReturn(Response("in-out"))
    }

    private fun remoteCommands(): List<String> {
        val commands = argumentCaptor<String>()
        verify(remoteOps, atLeast(0)).executeRemotely(any(), commands.capture(), any(), any())
        return commands.allValues
    }

    private fun failToolWith(failure: RemoteCommandFailedException) {
        whenever(remoteOps.executeRemotely(any(), argThat { startsWith(kubeconfig) }, any(), any())).doThrow(failure)
        whenever(remoteOps.executeRemotelyWithInput(any(), argThat { startsWith(kubeconfig) }, any(), any())).doThrow(failure)
    }

    private val applyFromInput = ToolArg.values("apply", "-f") + ToolArg.Input

    @Test
    fun `a command runs on the control node with its kubeconfig, shows output only when asked, and returns stdout`() {
        assertThat(tooling.run(ClusterTool.KUBECTL, listOf("get", "nodes"))).isEqualTo("out")
        tooling.run(ClusterTool.HELM, listOf("list", "-q"), echo = false)

        verify(remoteOps).executeRemotely(controlHost.toHost(), "$kubeconfig kubectl get nodes", true, false)
        verify(remoteOps).executeRemotely(controlHost.toHost(), "$kubeconfig helm list -q", false, false)
    }

    @Test
    fun `safe arguments are joined as they are, and any other one is quoted, an empty one included`() {
        tooling.run(ClusterTool.HELM, listOf("repo", "add", "my repo", "https://h/c?a=1&b=\$X", "", "--set", "k=v"))

        assertThat(remoteCommands()).containsExactly("$kubeconfig helm repo add 'my repo' 'https://h/c?a=1&b=\$X' '' --set k=v")
    }

    /** Property: whatever the value, a POSIX shell reading the rendered command hands the tool exactly that value. */
    @Test
    fun `every argument reaches the remote shell as exactly the original value`() {
        val awkward =
            listOf(
                "plain",
                "",
                "tier in (a, b)",
                "^release\$",
                "it's",
                "a\"b",
                "\$HOME `id` \$(id)",
                "semi;colon && pipe | star * glob ? hash # tilde ~",
                "back\\slash",
                "two\nlines",
            )

        awkward.forEach { value ->
            val remote: RemoteOperationsService = mock()
            whenever(remote.executeRemotely(any(), any(), any(), any())).doReturn(Response(""))
            SshClusterTooling(remote, stateManager).run(ClusterTool.HELM, listOf("x", value))
            val command = argumentCaptor<String>().also { verify(remote).executeRemotely(any(), it.capture(), any(), any()) }.firstValue
            val quoted = command.removePrefix("$kubeconfig helm x ")

            val process = ProcessBuilder("/bin/sh", "-c", "printf %s $quoted").redirectErrorStream(true).start()
            val printed = process.inputStream.readBytes().toString(Charsets.UTF_8)
            process.waitFor()

            assertThat(printed).describedAs("shell reading %s", quoted).isEqualTo(value)
        }
    }

    @Test
    fun `input is sent as the command's stdin, the input argument is a dash, and nothing else runs`() {
        val args = ToolArg.values("upgrade", "--install", "r", "chart", "--values") + ToolArg.Input + ToolArg.values("--set", "a=b")

        assertThat(tooling.runWithInput(ClusterTool.HELM, args, input = "x: 1\n", echo = false)).isEqualTo("in-out")

        verify(remoteOps).executeRemotelyWithInput(
            controlHost.toHost(),
            "$kubeconfig helm upgrade --install r chart --values - --set a=b",
            "x: 1\n",
            false,
        )
        assertThat(remoteCommands()).isEmpty()
        verify(remoteOps, never()).upload(any(), any(), any(), any())
    }

    @Test
    fun `a value that is a dash is passed as a value, not taken for the input`() {
        tooling.run(ClusterTool.KUBECTL, listOf("logs", "-f", "-"))
        tooling.runWithInput(ClusterTool.KUBECTL, ToolArg.values("apply", "-f") + ToolArg.Input + ToolArg.values("-l", "-"), input = "x")

        assertThat(remoteCommands()).containsExactly("$kubeconfig kubectl logs -f -")
        verify(remoteOps).executeRemotelyWithInput(any(), eq("$kubeconfig kubectl apply -f - -l -"), eq("x"), any())
    }

    @Test
    fun `input without exactly one input argument fails before touching the cluster, with credentials redacted`() {
        listOf(
            ToolArg.values("apply", "-f", "https://u:s3cret@example.com/m.yaml"),
            applyFromInput + ToolArg.values("-f") + ToolArg.Input,
        ).forEach { args ->
            assertThatThrownBy { tooling.runWithInput(ClusterTool.KUBECTL, args, input = "x") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("exactly one input argument")
                .hasMessageNotContaining("s3cret")
        }

        verifyNoInteractions(remoteOps)
    }

    @Test
    fun `a failing command with input becomes a cluster tool failure with its status and stderr`() {
        failToolWith(RemoteCommandFailedException("c", "", "error: bad manifest", "summary", exitStatus = 1))

        val failure =
            catchThrowableOfType(ClusterToolFailedException::class.java) {
                tooling.runWithInput(ClusterTool.KUBECTL, applyFromInput, input = "x")
            }

        assertThat(failure.exitStatus).isEqualTo(1)
        assertThat(failure.stderr).isEqualTo("error: bad manifest")
        assertThat(failure).hasMessageContaining("kubectl apply -f - failed on control0 (54.1.2.3)")
    }

    @Test
    fun `the control host is read again on every call`() {
        val newControl = clusterHost("54.9.9.9", "10.0.0.9")

        tooling.run(ClusterTool.KUBECTL, listOf("get", "nodes"))
        saveState(newControl)
        tooling.run(ClusterTool.KUBECTL, listOf("get", "nodes"))

        val hosts = argumentCaptor<Host>().also { verify(remoteOps, times(2)).executeRemotely(it.capture(), any(), any(), any()) }
        assertThat(hosts.allValues).containsExactly(controlHost.toHost(), newControl.toHost())
    }

    @Test
    fun `a cluster with no control node fails before touching the cluster`() {
        stateManager.replace(ClusterState(name = "test"))

        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("get", "nodes")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("No control node found. Please ensure the environment is running.")
        verifyNoInteractions(remoteOps)
    }

    @Test
    fun `a remote failure becomes a cluster tool failure with its status, output and the control node`() {
        failToolWith(RemoteCommandFailedException("c", "some stdout", "error: no such kind", "summary", exitStatus = 1))

        val failure = catchThrowableOfType(ClusterToolFailedException::class.java) { tooling.run(ClusterTool.KUBECTL, listOf("get", "x")) }

        assertThat(failure.tool).isEqualTo(ClusterTool.KUBECTL)
        assertThat(failure).hasMessageStartingWith("kubectl get x failed on control0 (54.1.2.3) with exit status 1")
        assertThat(failure.exitStatus).isEqualTo(1)
        assertThat(failure.stderr).isEqualTo("error: no such kind")
        assertThat(failure.stdout).isEqualTo("some stdout")
        assertThat(failure.location).isEqualTo("control0 (54.1.2.3)")
    }

    @Test
    fun `a failure names the control node by its private address when it has no public one, and keeps a missing status missing`() {
        saveState(clusterHost("", "10.0.0.1"))
        failToolWith(RemoteCommandFailedException("c", "", "", "summary"))

        val failure = catchThrowableOfType(ClusterToolFailedException::class.java) { tooling.run(ClusterTool.HELM, listOf("list")) }

        assertThat(failure.location).isEqualTo("control0 (10.0.0.1)")
        assertThat(failure.exitStatus).isNull()
    }
}
