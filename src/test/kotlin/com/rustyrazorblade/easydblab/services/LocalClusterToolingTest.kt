package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.PackagedExecutable
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * [LocalClusterTooling] against real processes. The workspace wrappers are a stand-in script that
 * does what its first argument says, so the tests need no helm, kubectl or Docker; the real tools
 * run in `LocalClusterToolingIntegrationTest`. The local form starts `<workspace>/bin/<tool>`, so the
 * stand-in is what it runs.
 */
internal class LocalClusterToolingTest {
    @TempDir
    lateinit var workspace: File

    private lateinit var tooling: LocalClusterTooling

    @BeforeEach
    fun setup() {
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText("apiVersion: v1\nkind: Config\n")
        val installer = ToolWrapperInstaller(wrapper = PackagedExecutable(STAND_IN_WRAPPER))
        tooling = LocalClusterTooling(workspace, KitProcessEnvironment(installer))
    }

    @Test
    fun `a shell-quoted index outside the arguments fails before a process starts`() {
        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("get"), shellQuoted = setOf(5)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("[5]")
        // applyTo installs <workspace>/bin before a process starts, so its absence shows nothing ran.
        assertThat(File(workspace, Constants.ToolWrappers.DIRECTORY)).doesNotExist()
    }

    @Test
    fun `inherited HELM_ variables and KUBECONFIG are cleared and other variables are kept`() {
        val environment =
            mutableMapOf(
                "HELM_REPOSITORY_CONFIG" to "/home/operator/.config/helm/repositories.yaml",
                "HELM_KUBECONTEXT" to "operator-context",
                "KUBECONFIG" to "/home/operator/.kube/config",
                "HOME" to "/home/operator",
                "MY_HELM_NOTE" to "kept",
            )

        clearInheritedToolEnvironment(environment)

        assertThat(environment).containsOnlyKeys("HOME", "MY_HELM_NOTE")
    }

    @Test
    fun `a failure carries the exit status and a stderr without URL credentials`() {
        val failure = catchThrowableOfType(ClusterToolFailedException::class.java) { tooling.run(ClusterTool.KUBECTL, listOf("fail")) }

        assertThat(failure.exitStatus).isEqualTo(FAILING_EXIT_STATUS)
        assertThat(failure.stderr).contains("https://***@host/m.yaml").doesNotContain("s3cret")
        assertThat(failure.message).contains("kubectl fail").contains("exit status $FAILING_EXIT_STATUS").doesNotContain("s3cret")
    }

    @Test
    fun `stdout has URL credentials redacted, as the SSH client does`() {
        val stdout = tooling.run(ClusterTool.HELM, listOf("leak"), echo = false)

        assertThat(stdout).contains("https://***@host/chart.tgz").doesNotContain("s3cret")
    }

    @Test
    fun `stdin reaches the process and its stdout comes back`() {
        val manifest = "apiVersion: v1\nkind: ConfigMap\n"

        assertThat(tooling.run(ClusterTool.KUBECTL, listOf("cat", "-"), stdin = manifest, echo = false)).isEqualTo(manifest)
    }

    @Test
    @Timeout(DEADLOCK_SECONDS)
    fun `a tool that fills both pipes before it reads its input does not deadlock`() {
        val stdout = tooling.run(ClusterTool.KUBECTL, listOf("flood"), stdin = "x".repeat(STDIN_BYTES), echo = false)

        assertThat(stdout.lines().count { it.startsWith("out ") }).isEqualTo(FLOOD_LINES)
    }

    @Test
    fun `helm keeps its config, cache and data under the workspace and kubectl uses the workspace kubeconfig`() {
        val environment =
            tooling.run(ClusterTool.HELM, listOf("env"), echo = false).lines().associate {
                it.substringBefore('=') to
                    it.substringAfter('=')
            }

        val helmRoot = File(workspace, Constants.ClusterTooling.HELM_HOME_DIRECTORY).absolutePath
        assertThat(environment)
            .containsEntry("HELM_CONFIG_HOME", "$helmRoot/config")
            .containsEntry("HELM_CACHE_HOME", "$helmRoot/cache")
            .containsEntry("HELM_DATA_HOME", "$helmRoot/data")
            .containsEntry("KUBECONFIG", File(workspace, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
    }

    @Test
    fun `echo shows stdout once the tool has finished and echo off shows nothing`() {
        val shown = capturingStdout { tooling.run(ClusterTool.KUBECTL, listOf("cat", "-"), stdin = "hello\n", echo = true) }
        val hidden = capturingStdout { tooling.run(ClusterTool.KUBECTL, listOf("cat", "-"), stdin = "hello\n", echo = false) }

        assertThat(shown).isEqualTo("hello" + System.lineSeparator())
        assertThat(hidden).isEmpty()
    }

    private fun capturingStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    private companion object {
        const val FAILING_EXIT_STATUS = 3
        const val FLOOD_LINES = 20_000
        const val STDIN_BYTES = 1_000_000
        const val DEADLOCK_SECONDS = 60L

        /** Does what its first argument says, whichever tool name it is installed under. */
        val STAND_IN_WRAPPER =
            """
            #!/bin/sh
            case "${'$'}1" in
                fail) echo "error: unable to read https://user:s3cret@host/m.yaml" >&2; exit $FAILING_EXIT_STATUS ;;
                leak) echo "pulled https://user:s3cret@host/chart.tgz" ;;
                cat) cat ;;
                flood)
                    i=0
                    while [ ${'$'}i -lt $FLOOD_LINES ]; do
                        echo "out ${'$'}i padding padding padding padding padding"
                        echo "err ${'$'}i padding padding padding padding padding" >&2
                        i=${'$'}((i + 1))
                    done
                    cat > /dev/null
                    ;;
                env)
                    echo "HELM_CONFIG_HOME=${'$'}HELM_CONFIG_HOME"
                    echo "HELM_CACHE_HOME=${'$'}HELM_CACHE_HOME"
                    echo "HELM_DATA_HOME=${'$'}HELM_DATA_HOME"
                    echo "KUBECONFIG=${'$'}KUBECONFIG"
                    ;;
            esac
            """.trimIndent() + "\n"
    }
}
