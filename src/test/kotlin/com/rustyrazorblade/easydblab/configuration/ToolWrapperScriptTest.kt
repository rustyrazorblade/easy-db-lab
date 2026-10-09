package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The packaged tool wrapper, run for real through `/bin/sh` (and dash, where it is installed) against
 * stub binaries that print what they received.
 *
 * Each case builds a workspace whose `bin/` holds the six wrapper copies and the marker, with the
 * stubs in a directory later on `PATH`, the way a kit shell step or an `env.sh` shell sees them.
 */
internal class ToolWrapperScriptTest {
    @TempDir
    lateinit var root: File

    private lateinit var workspace: File
    private lateinit var realBin: File

    @BeforeEach
    fun setUp() {
        workspace = workspace("ws")
        realBin = File(root, "real").apply { mkdirs() }
        (Constants.ToolWrappers.TOOLS + "aws").forEach { stub(it) }
    }

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("toolsAndShells")
    fun `each tool gets its own proxy variables in both cases`(
        tool: String,
        shell: String,
    ) {
        socksCluster(port = 41234)

        val result = run("$shell \"${'$'}WS/bin/$tool\" probe", env = mapOf("WS" to workspace.absolutePath))

        assertThat(result.exitCode).withFailMessage(result.toString()).isZero()
        assertThat(result.proxyVariables()).isEqualTo(EXPECTED.getValue(tool))
    }

    @Test
    fun `inherited proxy variables are replaced, not added to`() {
        socksCluster(port = 41234)
        val hostile =
            mapOf(
                "NO_PROXY" to "*",
                "no_proxy" to "10.0.0.0/8",
                "http_proxy" to "http://corporate:3128",
                "ALL_PROXY" to "socks5://elsewhere:1080",
                "HTTPS_PROXY" to "http://corporate:3128",
            )

        val kubectl = run("kubectl get ns", env = hostile)
        val curl = run("curl http://10.0.1.5:3000/", env = hostile)

        assertThat(kubectl.proxyVariables()).isEqualTo(EXPECTED.getValue("kubectl"))
        assertThat(curl.proxyVariables()).isEqualTo(EXPECTED.getValue("curl"))
    }

    @Test
    fun `on a Tailscale cluster the real binary gets the environment unchanged`() {
        ProxyEnvFile(workspace).recordTailscale(active = true)
        val inherited = mapOf("NO_PROXY" to "*", "http_proxy" to "http://corporate:3128", "OPERATOR_VAR" to "kept")

        val direct = run("${realBin.absolutePath}/kubectl get ns", env = inherited)
        val wrapped = run("kubectl get ns", env = inherited)

        assertThat(wrapped.exitCode).withFailMessage(wrapped.toString()).isZero()
        assertThat(wrapped.environment()).isEqualTo(direct.environment())
        assertThat(wrapped.environment()).contains("NO_PROXY=*", "http_proxy=http://corporate:3128", "OPERATOR_VAR=kept")
    }

    @Test
    fun `a port change between two calls is picked up by the next call`() {
        socksCluster(port = 41234)
        val first = run("kubectl get ns")

        ProxyEnvFile(workspace).recordPort(45678)
        val second = run("kubectl get ns")

        assertThat(first.stdout).contains("HTTPS_PROXY=socks5://localhost:41234")
        assertThat(second.stdout).contains("HTTPS_PROXY=socks5://localhost:45678")
    }

    @Test
    fun `with no port recorded the wrapper fails and names the workspace and start-socks`() {
        ProxyEnvFile(workspace).recordTailscale(active = false)

        val result = run("kubectl get ns")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stdout).doesNotContain("tool=")
        assertThat(result.stderr).contains(workspace.canonicalPath).contains("easy-db-lab start-socks")
    }

    @Test
    fun `with no env file at all the wrapper fails the same way`() {
        val result = run("helm list -A")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("easy-db-lab start-socks")
    }

    @Test
    fun `with no real binary on PATH the wrapper exits 127 with a message naming the tool`() {
        socksCluster(port = 41234)
        File(realBin, "skopeo").delete()

        val result = run("skopeo inspect docker://x")

        assertThat(result.exitCode).isEqualTo(NOT_FOUND)
        assertThat(result.stderr).contains("skopeo")
    }

    @Test
    fun `two workspaces on PATH do not call each other`() {
        socksCluster(port = 41234)
        val other = workspace("other")
        ProxyEnvFile(other).apply {
            recordTailscale(active = false)
            recordPort(45678)
        }

        val result = run("kubectl get ns", path = listOf(File(workspace, "bin"), File(other, "bin"), realBin))

        assertThat(result.exitCode).withFailMessage(result.toString()).isZero()
        assertThat(result.stdout.lines().filter { it == "tool=kubectl" }).hasSize(1)
        assertThat(result.stdout).contains("HTTPS_PROXY=socks5://localhost:41234")
    }

    @Test
    fun `a workspace reached through a symlink finds its env file`() {
        socksCluster(port = 41234)
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), workspace.toPath())

        val result = run("kubectl get ns", path = listOf(File(link, "bin"), realBin))

        assertThat(result.exitCode).withFailMessage(result.toString()).isZero()
        assertThat(result.stdout).contains("HTTPS_PROXY=socks5://localhost:41234")
    }

    @Test
    fun `a CDPATH in the environment does not send a relative call to another workspace`() {
        socksCluster(port = 41234)
        val decoy = workspace("decoy")
        ProxyEnvFile(decoy).apply {
            recordTailscale(active = false)
            recordPort(45678)
        }

        val result =
            run(
                "cd '${workspace.absolutePath}' && bin/kubectl get ns",
                env = mapOf("CDPATH" to decoy.absolutePath),
            )

        assertThat(result.exitCode).withFailMessage(result.toString()).isZero()
        assertThat(result.stdout).contains("HTTPS_PROXY=socks5://localhost:41234")
    }

    @Test
    fun `arguments with spaces, stdin and a non-zero exit code pass through`() {
        socksCluster(port = 41234)

        val result =
            run(
                "kubectl apply -f - --selector 'app in (a, b)' ''",
                env = mapOf("STUB_READ_STDIN" to "1", "STUB_EXIT" to "3"),
                stdin = "kind: Namespace\n",
            )

        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.arguments()).containsExactly("apply", "-f", "-", "--selector", "app in (a, b)", "")
        assertThat(result.stdout).contains("stdin=kind: Namespace")
    }

    @Test
    fun `indirect calls go through the wrapper`() {
        socksCluster(port = 41234)
        val kitBin = File(workspace, "mykit/bin").apply { mkdirs() }
        val nested =
            File(kitBin, "status.sh").apply {
                writeText("#!/bin/sh\nexec kubectl get pods\n")
                setExecutable(true)
            }
        val outer =
            File(root, "outer.sh").apply {
                writeText("#!/bin/sh\n\"${nested.absolutePath}\"\n")
                setExecutable(true)
            }

        val calls =
            listOf(
                "env kubectl get ns",
                "echo ns | xargs kubectl get",
                "sh -c 'kubectl get ns'",
                "/bin/sh '${outer.absolutePath}'",
                "'${nested.absolutePath}'",
            )

        calls.forEach { call ->
            val result = run(call)
            assertThat(result.exitCode).withFailMessage("$call: $result").isZero()
            assertThat(result.stdout).withFailMessage("$call: $result").contains("HTTPS_PROXY=socks5://localhost:41234")
        }
    }

    @Test
    fun `an unwrapped tool run by the same step sees no proxy variables`() {
        socksCluster(port = 41234)

        val result = run("kubectl get ns >/dev/null && aws sts get-caller-identity")

        assertThat(result.stdout).contains("tool=aws")
        assertThat(result.proxyVariables()).isEqualTo(NONE)
    }

    @Test
    fun `inherited EDL values are ignored in favour of the env file`() {
        socksCluster(port = 41234)

        val result = run("kubectl get ns", env = mapOf("EDL_TAILSCALE_ACTIVE" to "true", "EDL_SOCKS_PORT" to "9"))

        assertThat(result.stdout).contains("HTTPS_PROXY=socks5://localhost:41234")
    }

    @Test
    fun `an inherited port does not stand in for a missing one`() {
        ProxyEnvFile(workspace).recordTailscale(active = false)

        val result = run("kubectl get ns", env = mapOf("EDL_SOCKS_PORT" to "41234"))

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("easy-db-lab start-socks")
    }

    private fun socksCluster(port: Int) {
        ProxyEnvFile(workspace).apply {
            recordTailscale(active = false)
            recordPort(port)
        }
    }

    /** A workspace whose `bin/` holds the six wrapper copies and the marker. */
    private fun workspace(name: String): File =
        File(root, name).apply {
            val bin = File(this, Constants.ToolWrappers.DIRECTORY).apply { mkdirs() }
            File(bin, Constants.ToolWrappers.MARKER).writeText("")
            Constants.ToolWrappers.TOOLS.forEach { tool ->
                File(bin, tool).apply {
                    writeText(WRAPPER)
                    setExecutable(true)
                }
            }
        }

    /** A stand-in for a real binary: prints its name, its proxy variables, its arguments, and optionally stdin. */
    private fun stub(tool: String) {
        File(realBin, tool).apply {
            writeText(
                """
                |#!/bin/sh
                |echo "tool=${'$'}{0##*/}"
                |for v in ${PROXY_VARIABLES.joinToString(" ")}; do eval "val=\${'$'}{${'$'}v-unset}"; echo "${'$'}v=${'$'}val"; done
                |for a in "${'$'}@"; do echo "arg=${'$'}a"; done
                |if [ -n "${'$'}{STUB_READ_STDIN:-}" ]; then echo "stdin=${'$'}(cat)"; fi
                |if [ -n "${'$'}{STUB_PRINT_ENV:-}" ]; then env | sed 's/^/env:/'; fi
                |exit "${'$'}{STUB_EXIT:-0}"
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
    }

    private fun run(
        command: String,
        env: Map<String, String> = emptyMap(),
        stdin: String = "",
        path: List<File> = listOf(File(workspace, Constants.ToolWrappers.DIRECTORY), realBin),
    ): Result {
        val builder = ProcessBuilder("/bin/sh", "-c", command).directory(root)
        builder.environment().apply {
            clear()
            put("PATH", (path.map { it.absolutePath } + SYSTEM_PATH).joinToString(File.pathSeparator))
            put("STUB_PRINT_ENV", "1")
            putAll(env)
        }
        val process = builder.start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertThat(process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("$command did not finish").isTrue()
        return Result(process.exitValue(), stdout, stderr)
    }

    /** What one run printed: the stubs' lines on stdout, the wrapper's messages on stderr. */
    private data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        /** The proxy variables the last stub saw, as `NAME=value` with `unset` for an absent one. */
        fun proxyVariables(): Map<String, String> =
            stdout
                .lines()
                .map { it.split('=', limit = 2) }
                .filter { it.size == 2 && it[0] in PROXY_VARIABLES }
                .associate { (name, value) -> name to value }

        fun arguments(): List<String> = stdout.lines().filter { it.startsWith("arg=") }.map { it.removePrefix("arg=") }

        /** The stub's environment, minus what any shell sets on its own. */
        fun environment(): List<String> =
            stdout
                .lines()
                .filter { it.startsWith("env:") }
                .map { it.removePrefix("env:") }
                .filterNot { line -> SHELL_BOOKKEEPING.any { line.startsWith("$it=") } }
                .sorted()
    }

    private companion object {
        const val NOT_FOUND = 127
        const val LIMIT_SECONDS = 30L
        val SYSTEM_PATH = listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")
        val SHELL_BOOKKEEPING = listOf("SHLVL", "_", "PWD", "OLDPWD")

        val WRAPPER: String =
            requireNotNull(ToolWrapperScriptTest::class.java.getResource(Constants.ToolWrappers.RESOURCE)) {
                "Missing packaged resource ${Constants.ToolWrappers.RESOURCE}"
            }.readText()

        val PROXY_VARIABLES =
            listOf("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy")

        val NONE = PROXY_VARIABLES.associateWith { "unset" }

        private const val SOCKS = "socks5://localhost:41234"
        private const val SOCKS_H = "socks5h://localhost:41234"
        private const val LOCAL = "localhost,127.0.0.1"

        private val TUNNEL_ONLY = NONE + mapOf("HTTPS_PROXY" to SOCKS, "https_proxy" to SOCKS)

        val EXPECTED: Map<String, Map<String, String>> =
            mapOf(
                "kubectl" to TUNNEL_ONLY,
                "helm" to TUNNEL_ONLY,
                "cilium" to TUNNEL_ONLY,
                "k9s" to TUNNEL_ONLY,
                "curl" to NONE + mapOf("ALL_PROXY" to SOCKS_H, "all_proxy" to SOCKS_H, "NO_PROXY" to LOCAL, "no_proxy" to LOCAL),
                "skopeo" to
                    NONE +
                    listOf("ALL_PROXY", "all_proxy", "HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy").associateWith { SOCKS_H } +
                    mapOf("NO_PROXY" to LOCAL, "no_proxy" to LOCAL),
            )

        /** Every tool under `/bin/sh`, and under dash too where it is installed (it is `/bin/sh` on Debian and Ubuntu). */
        @JvmStatic
        fun toolsAndShells(): List<Arguments> {
            val shells = listOf("/bin/sh", "/bin/dash").filter { File(it).canExecute() }
            return Constants.ToolWrappers.TOOLS.flatMap { tool -> shells.map { Arguments.of(tool, it) } }
        }
    }
}
