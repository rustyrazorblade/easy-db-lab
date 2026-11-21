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
import org.mockito.kotlin.doAnswer
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
import java.nio.file.Files
import java.nio.file.Path

/**
 * [SshClusterTooling] against a mocked [RemoteOperationsService], which CLAUDE.md allows for remote
 * CLI tools, and a real [ClusterStateManager] over a `@TempDir` state file, so a change of cluster
 * state is a real one.
 */
internal class SshClusterToolingTest {
    @TempDir
    lateinit var stateDir: File

    @TempDir
    lateinit var tempDir: File

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
        tooling = SshClusterTooling(remoteOps, stateManager, tempDir.toPath())
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response("out"))
    }

    private fun remoteCommands(): List<String> {
        val commands = argumentCaptor<String>()
        verify(remoteOps, atLeast(0)).executeRemotely(any(), commands.capture(), any(), any())
        return commands.allValues
    }

    private fun uploadedPath(): String =
        argumentCaptor<String>()
            .also {
                verify(remoteOps).upload(any(), any(), it.capture(), any())
            }.firstValue

    private fun failToolWith(failure: RemoteCommandFailedException) {
        whenever(remoteOps.executeRemotely(any(), argThat { startsWith(kubeconfig) }, any(), any())).doThrow(failure)
    }

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
            SshClusterTooling(remote, stateManager, tempDir.toPath()).run(ClusterTool.HELM, listOf("x", value))
            val command = argumentCaptor<String>().also { verify(remote).executeRemotely(any(), it.capture(), any(), any()) }.firstValue
            val quoted = command.removePrefix("$kubeconfig helm x ")

            val process = ProcessBuilder("/bin/sh", "-c", "printf %s $quoted").redirectErrorStream(true).start()
            val printed = process.inputStream.readBytes().toString(Charsets.UTF_8)
            process.waitFor()

            assertThat(printed).describedAs("shell reading %s", quoted).isEqualTo(value)
        }
    }

    @Test
    fun `stdin is uploaded, the dash becomes the remote path, and the remote file is removed quietly afterwards`() {
        val uploads = mutableListOf<Pair<Path, String>>()
        doAnswer { uploads += it.getArgument<Path>(1).let { local -> local to Files.readString(local) } }
            .whenever(remoteOps)
            .upload(any(), any(), any(), any())

        tooling.run(ClusterTool.HELM, listOf("upgrade", "--install", "r", "chart", "--values", "-", "--set", "a=b"), stdin = "x: 1\n")

        val path = uploadedPath()
        assertThat(path).matches("/tmp/easydblab-input-[0-9a-f-]{36}\\.yaml")
        assertThat(uploads.single().second).isEqualTo("x: 1\n")
        assertThat(uploads.single().first.parent).isEqualTo(tempDir.toPath())
        assertThat(remoteCommands()).containsExactly(
            "$kubeconfig helm upgrade --install r chart --values $path --set a=b",
            "rm -f $path",
        )
        verify(remoteOps).executeRemotely(any(), eq("rm -f $path"), eq(false), any())
        assertThat(tempDir.list()).isEmpty()
    }

    @Test
    fun `both files are removed when the command fails, and when the upload fails`() {
        failToolWith(RemoteCommandFailedException("c", "", "boom", "summary", exitStatus = 1))
        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x: 1\n") }
            .isInstanceOf(ClusterToolFailedException::class.java)
        assertThat(remoteCommands().last()).isEqualTo("rm -f ${uploadedPath()}")
        assertThat(tempDir.list()).isEmpty()

        val failedUpload: RemoteOperationsService = mock()
        doThrow(IllegalStateException("scp down")).whenever(failedUpload).upload(any(), any(), any(), any())
        assertThatThrownBy {
            SshClusterTooling(
                failedUpload,
                stateManager,
                tempDir.toPath(),
            ).run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x")
        }.hasMessage("scp down")
        verify(failedUpload).executeRemotely(any(), argThat { startsWith("rm -f /tmp/easydblab-input-") }, eq(false), any())
        assertThat(tempDir.list()).isEmpty()
    }

    @Test
    fun `a failing cleanup never replaces the tool's own result`() {
        whenever(remoteOps.executeRemotely(any(), argThat { startsWith("rm -f ") }, any(), any()))
            .doThrow(IllegalStateException("connection lost"))

        assertThat(tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x")).isEqualTo("out")

        failToolWith(RemoteCommandFailedException("c", "", "the real error", "summary", exitStatus = 7))
        val failure =
            catchThrowableOfType(ClusterToolFailedException::class.java) {
                tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x")
            }
        assertThat(failure.exitStatus).isEqualTo(7)
        assertThat(failure.stderr).isEqualTo("the real error")
    }

    @Test
    fun `an interrupt during cleanup is not swallowed`() {
        // doAnswer, not doThrow: Mockito rejects a checked exception the Kotlin method does not declare.
        doAnswer { throw InterruptedException("cancelled") }
            .whenever(remoteOps)
            .executeRemotely(any(), argThat { startsWith("rm -f ") }, any(), any())

        try {
            assertThat(tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x")).isEqualTo("out")
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted() // clear the flag so it cannot leak into the next test on this thread
        }
    }

    @Test
    fun `stdin without exactly one dash fails before touching the cluster, with credentials redacted`() {
        listOf(listOf("apply", "-f", "https://u:s3cret@example.com/m.yaml"), listOf("apply", "-f", "-", "-f", "-")).forEach { args ->
            assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, args, stdin = "x") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageNotContaining("s3cret")
        }

        verifyNoInteractions(remoteOps)
        assertThat(tempDir.list()).isEmpty()
    }

    @Test
    fun `a dash argument without stdin is passed through untouched`() {
        tooling.run(ClusterTool.KUBECTL, listOf("logs", "-f", "-"))

        verify(remoteOps, never()).upload(any(), any(), any(), any())
        assertThat(remoteCommands()).containsExactly("$kubeconfig kubectl logs -f -")
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
        assertThat(failure.args).containsExactly("get", "x")
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
