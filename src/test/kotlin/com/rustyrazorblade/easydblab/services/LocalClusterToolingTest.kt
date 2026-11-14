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
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private lateinit var kitEnvironment: KitProcessEnvironment

    @BeforeEach
    fun setup() {
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText("apiVersion: v1\nkind: Config\n")
        kitEnvironment = KitProcessEnvironment(ToolWrapperInstaller(wrapper = PackagedExecutable(STAND_IN_WRAPPER)))
        tooling = LocalClusterTooling(workspace, kitEnvironment)
    }

    @Test
    fun `a shell-quoted index outside the arguments fails before a process starts`() {
        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("get"), shellQuoted = setOf(5)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("[5]")
        // applyTo installs <workspace>/bin before a process starts, so its absence shows nothing ran.
        assertThat(File(workspace, Constants.ToolWrappers.DIRECTORY)).doesNotExist()
    }

    /** The SSH form needs the `-` to stand for its uploaded file, so the local form refuses the same call. */
    @Test
    fun `stdin without exactly one dash argument fails before a process starts, as in the SSH form`() {
        listOf(listOf("apply", "-f", "m.yaml"), listOf("apply", "-f", "-", "-f", "-")).forEach { args ->
            assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, args, stdin = "kind: ConfigMap") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("exactly one '-'")
        }
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
    fun `a call starts without the operator's HELM_ variables and KUBECONFIG, and keeps the rest`() {
        val operator =
            mapOf(
                "PATH" to System.getenv("PATH").orEmpty(),
                "HELM_REPOSITORY_CONFIG" to "/home/operator/.config/helm/repositories.yaml",
                "HELM_KUBECONTEXT" to "operator-context",
                "KUBECONFIG" to "/home/operator/.kube/config",
                "OPERATOR_NOTE" to "kept",
            )

        val seen =
            LocalClusterTooling(workspace, kitEnvironment, inheritedEnvironment = operator)
                .run(ClusterTool.HELM, listOf("dump"), echo = false)
                .lines()
                .associate { it.substringBefore('=') to it.substringAfter('=') }

        assertThat(seen)
            .doesNotContainKeys("HELM_REPOSITORY_CONFIG", "HELM_KUBECONTEXT")
            .containsEntry("OPERATOR_NOTE", "kept")
            .containsEntry("KUBECONFIG", File(workspace, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
    }

    @Test
    @Timeout(DEADLOCK_SECONDS)
    fun `a tool that exits at once without reading a large stdin still reports its own failure`() {
        val failure =
            catchThrowableOfType(ClusterToolFailedException::class.java) {
                tooling.run(ClusterTool.KUBECTL, listOf("quit", "-"), stdin = "x".repeat(STDIN_BYTES), echo = false)
            }

        assertThat(failure.exitStatus).isEqualTo(FAILING_EXIT_STATUS)
    }

    @Test
    fun `interrupting the caller kills the running tool and frees the caller`() {
        val outcome = AtomicReference<Throwable>()
        val caller =
            Thread {
                try {
                    tooling.run(ClusterTool.KUBECTL, listOf("hang"), echo = false)
                } catch (e: InterruptedException) {
                    outcome.set(e)
                }
            }
        var pid: Long? = null
        try {
            caller.start()
            pid = awaitPid(File(workspace, "hang.pid"))
            caller.interrupt()
            caller.join(FREED_MILLIS)

            assertThat(caller.isAlive).describedAs("the caller is still waiting on the tool").isFalse()
            assertThat(outcome.get()).isInstanceOf(InterruptedException::class.java)
            assertThat(awaitExit(pid)).describedAs("the tool is still running").isTrue()
        } finally {
            // A tool left running by a failed assertion must not outlive the test.
            pid?.let { ProcessHandle.of(it).ifPresent { handle -> handle.destroyForcibly() } }
        }
    }

    @Test
    @Timeout(DEADLOCK_SECONDS)
    fun `a tool that runs past the timeout is killed and the failure names it, its arguments and the timeout`() {
        val bounded = LocalClusterTooling(workspace, kitEnvironment, timeout = Duration.ofMillis(TIMEOUT_MILLIS))
        var pid: Long? = null
        try {
            val failure =
                catchThrowableOfType(ClusterToolTimedOutException::class.java) {
                    bounded.run(ClusterTool.KUBECTL, listOf("hang", "https://user:s3cret@host/m.yaml"), echo = false)
                }
            pid = recordedPid()

            assertThat(failure.tool).isEqualTo(ClusterTool.KUBECTL)
            assertThat(failure.message)
                .contains("kubectl hang https://***@host/m.yaml")
                .contains("PT0.5S")
                .doesNotContain("s3cret")
            pid?.let { assertThat(awaitExit(it)).describedAs("the tool is still running").isTrue() }
        } finally {
            pid?.let { ProcessHandle.of(it).ifPresent { handle -> handle.destroyForcibly() } }
        }
    }

    /** The bound covers feeding stdin too: a hung tool that never reads a large input still times out. */
    @Test
    @Timeout(DEADLOCK_SECONDS)
    fun `a tool that hangs without reading a large stdin still times out`() {
        val bounded = LocalClusterTooling(workspace, kitEnvironment, timeout = Duration.ofMillis(TIMEOUT_MILLIS))

        assertThatThrownBy { bounded.run(ClusterTool.KUBECTL, listOf("hang", "-"), stdin = "x".repeat(STDIN_BYTES), echo = false) }
            .isInstanceOf(ClusterToolTimedOutException::class.java)
        recordedPid()?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }

    @Test
    fun `a failure carries the stdout too, with URL credentials redacted`() {
        val failure = catchThrowableOfType(ClusterToolFailedException::class.java) { tooling.run(ClusterTool.HELM, listOf("failout")) }

        assertThat(failure.stdout).isEqualTo("stdout: pulled https://***@host/chart.tgz")
        assertThat(
            failure.message,
        ).contains("stderr:\nstderr: boom", "stdout:\nstdout: pulled https://***@host/chart.tgz").doesNotContain("s3cret")
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
        val stdout = tooling.run(ClusterTool.KUBECTL, listOf("flood", "-"), stdin = "x".repeat(STDIN_BYTES), echo = false)

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

    /**
     * The pid the `hang` stand-in wrote, or null when it wrote none. On a loaded machine a short timeout
     * can kill the stand-in before it writes its pid; it then never became `sleep`, so no tool outlives
     * the call and nothing needs cleaning up.
     */
    private fun recordedPid(): Long? =
        File(workspace, "hang.pid")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toLongOrNull()

    /** The pid the `hang` stand-in wrote before it became `sleep`, once it has written it. */
    private fun awaitPid(file: File): Long {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FREED_MILLIS)
        while (System.nanoTime() < deadline) {
            file
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.toLongOrNull()
                ?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error("The stand-in never wrote ${file.name}")
    }

    /** Whether the process [pid] is gone within [FREED_MILLIS]. */
    private fun awaitExit(pid: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FREED_MILLIS)
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) != true) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
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
        const val HANG_SECONDS = 120
        const val FREED_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val TIMEOUT_MILLIS = 500L

        /** Does what its first argument says, whichever tool name it is installed under. */
        val STAND_IN_WRAPPER =
            """
            #!/bin/sh
            case "${'$'}1" in
                fail) echo "error: unable to read https://user:s3cret@host/m.yaml" >&2; exit $FAILING_EXIT_STATUS ;;
                failout)
                    echo "stdout: pulled https://user:s3cret@host/chart.tgz"
                    echo "stderr: boom" >&2
                    exit $FAILING_EXIT_STATUS
                    ;;
                quit) exit $FAILING_EXIT_STATUS ;;
                hang)
                    echo ${'$'}${'$'} > hang.pid
                    exec sleep $HANG_SECONDS
                    ;;
                dump) env ;;
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
