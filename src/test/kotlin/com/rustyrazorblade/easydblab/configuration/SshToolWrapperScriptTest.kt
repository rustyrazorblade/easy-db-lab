package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The `ssh`, `scp`, `sftp` and `rsync` copies of the packaged tool wrapper, run for real through
 * `/bin/sh` (and dash, where it is installed) against stub binaries that print what they received.
 *
 * They reach the nodes with the workspace `sshConfig`, as the `env.sh` functions they replace did, and
 * leave the proxy variables and `KUBECONFIG` alone: SSH never goes through the SOCKS tunnel.
 */
internal class SshToolWrapperScriptTest {
    @TempDir
    lateinit var root: File

    private lateinit var workspace: File
    private lateinit var realBin: File
    private val sshConfig: File get() = File(workspace.canonicalFile, "sshConfig")

    @BeforeEach
    fun setUp() {
        workspace =
            File(root, "ws").apply {
                val bin = File(this, Constants.ToolWrappers.DIRECTORY).apply { mkdirs() }
                File(bin, Constants.ToolWrappers.MARKER).writeText("")
                Constants.ToolWrappers.WRAPPED_TOOLS.forEach { tool ->
                    File(bin, tool).apply {
                        writeText(WRAPPER)
                        setExecutable(true)
                    }
                }
                File(this, "sshConfig").writeText("Host control0\n  Hostname 10.0.1.5\n")
            }
        realBin = File(root, "real").apply { mkdirs() }
        Constants.ToolWrappers.SSH_TOOLS.forEach { stub(it) }
        // A recorded SOCKS tunnel, which the Kubernetes tools would route through and these must not.
        ProxyEnvFile(workspace).apply {
            recordTailscale(active = false)
            recordTunnel(PORT, ProcessHandle.current().pid())
        }
    }

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("fileTransfersAndShells")
    fun `ssh, scp and sftp get the workspace sshConfig before the caller's arguments`(
        tool: String,
        shell: String,
    ) {
        val result = run("$shell \"${'$'}WS/bin/$tool\" control0 'two words'")

        assertThat(result.exitCode).withFailMessage(result.toString()).isZero()
        assertThat(result.lines("tool=")).containsExactly(tool)
        assertThat(result.lines("arg=")).containsExactly("-F", sshConfig.path, "control0", "two words")
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `rsync gets -ave with an ssh that uses the workspace sshConfig`(shell: String) {
        val result = run("$shell \"${'$'}WS/bin/rsync\" ./artifacts control0:/tmp/")

        assertThat(result.exitCode).withFailMessage(result.toString()).isZero()
        assertThat(result.lines("arg=")).containsExactly("-ave", "ssh -F ${sshConfig.path}", "./artifacts", "control0:/tmp/")
    }

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("toolsAndShells")
    fun `a missing sshConfig fails with exit 1, names the file and does not run the real binary`(
        tool: String,
        shell: String,
    ) {
        sshConfig.delete()

        val result = run("$shell \"${'$'}WS/bin/$tool\" control0")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains(sshConfig.path)
        assertThat(result.stdout).doesNotContain("tool=")
    }

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("toolsAndShells")
    fun `the proxy variables and KUBECONFIG reach the real binary as the caller had them`(
        tool: String,
        shell: String,
    ) {
        val inherited = mapOf("HTTPS_PROXY" to "http://corporate:3128", "KUBECONFIG" to "/home/operator/.kube/config")

        val result = run("$shell \"${'$'}WS/bin/$tool\" control0", env = inherited)

        assertThat(result.lines("env:").filter { it.startsWith("HTTPS_PROXY=") || it.startsWith("KUBECONFIG=") })
            .containsExactlyInAnyOrder("HTTPS_PROXY=http://corporate:3128", "KUBECONFIG=/home/operator/.kube/config")
        assertThat(result.lines("env:")).noneMatch { line ->
            listOf(
                "ALL_PROXY=",
                "all_proxy=",
                "https_proxy=",
                "HTTP_PROXY=",
                "http_proxy=",
                "NO_PROXY=",
                "no_proxy=",
            ).any { line.startsWith(it) }
        }
    }

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("toolsAndShells")
    fun `the wrapper runs the real binary once and never itself`(
        tool: String,
        shell: String,
    ) {
        // The wrapper directory is on PATH ahead of the real binaries, as in an env.sh shell.
        val result = run("$shell \"${'$'}WS/bin/$tool\" control0")

        assertThat(result.lines("tool=")).containsExactly(tool)
        assertThat(result.lines("arg=").count { it == "-F" || it == "-ave" }).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("toolsAndShells")
    fun `with no real binary on PATH the wrapper exits 127 naming the tool`(
        tool: String,
        shell: String,
    ) {
        File(realBin, tool).delete()
        // No system directory on PATH: every system has a real ssh, scp, sftp and rsync there (/bin is
        // /usr/bin on Ubuntu). Only the utility the wrapper itself needs is offered, from a directory of its own.
        val utilities =
            File(root, "utilities").apply {
                mkdirs()
                val dirname = requireNotNull(SYSTEM_PATH.map { File(it, "dirname") }.firstOrNull { it.canExecute() }) { "no dirname" }
                Files.createSymbolicLink(File(this, "dirname").toPath(), dirname.toPath())
            }

        val result = run("$shell \"${'$'}WS/bin/$tool\" control0", systemPath = listOf(utilities.absolutePath))

        assertThat(result.exitCode).isEqualTo(NOT_FOUND)
        assertThat(result.stderr).contains(tool)
    }

    /** A stand-in for a real binary: prints its name, its environment and its arguments. */
    private fun stub(tool: String) {
        File(realBin, tool).apply {
            writeText(
                """
                |#!/bin/sh
                |echo "tool=${'$'}{0##*/}"
                |env | sed 's/^/env:/'
                |for a in "${'$'}@"; do echo "arg=${'$'}a"; done
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
    }

    private fun run(
        command: String,
        env: Map<String, String> = emptyMap(),
        systemPath: List<String> = SYSTEM_PATH,
    ): Result {
        val builder = ProcessBuilder("/bin/sh", "-c", command).directory(root)
        builder.environment().apply {
            clear()
            val path = listOf(File(workspace, Constants.ToolWrappers.DIRECTORY).absolutePath, realBin.absolutePath) + systemPath
            put("PATH", path.joinToString(File.pathSeparator))
            put("WS", workspace.absolutePath)
            putAll(env)
        }
        val process = builder.start()
        process.outputStream.close()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertThat(process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("$command did not finish").isTrue()
        return Result(process.exitValue(), stdout, stderr)
    }

    /** What one run printed: the stub's lines on stdout, the wrapper's messages on stderr. */
    private data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        /** The stub's lines that start with [prefix], without it. */
        fun lines(prefix: String): List<String> = stdout.lines().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    companion object {
        private const val PORT = 41234
        private const val NOT_FOUND = 127
        private const val LIMIT_SECONDS = 30L
        private val SYSTEM_PATH = listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")

        private val WRAPPER: String =
            requireNotNull(SshToolWrapperScriptTest::class.java.getResource(Constants.ToolWrappers.RESOURCE)) {
                "Missing packaged resource ${Constants.ToolWrappers.RESOURCE}"
            }.readText()

        private val SHELLS = listOf("/bin/sh", "/bin/dash").filter { File(it).canExecute() }

        @JvmStatic
        fun shells(): List<String> = SHELLS

        @JvmStatic
        fun toolsAndShells(): List<Arguments> = Constants.ToolWrappers.SSH_TOOLS.withShells()

        @JvmStatic
        fun fileTransfersAndShells(): List<Arguments> = (Constants.ToolWrappers.SSH_TOOLS - "rsync").withShells()

        private fun List<String>.withShells(): List<Arguments> = flatMap { tool -> SHELLS.map { Arguments.of(tool, it) } }
    }
}
