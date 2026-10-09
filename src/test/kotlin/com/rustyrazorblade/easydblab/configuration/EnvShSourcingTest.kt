package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The generated `env.sh`, sourced for real in bash from a workspace written the way `up` writes it.
 * zsh is checked by hand; CI runs bash.
 */
internal class EnvShSourcingTest {
    @TempDir
    lateinit var workspace: File

    private val tools = Constants.ToolWrappers.TOOLS.joinToString(" ")

    @BeforeEach
    fun writeWorkspace() {
        val hosts =
            mapOf(
                ServerType.Control to listOf(ClusterHost("54.1.1.1", "10.0.0.1", "control0", "us-west-2a")),
                ServerType.Cassandra to listOf(ClusterHost("54.1.1.2", "10.0.0.2", "db0", "us-west-2a")),
            )
        File(workspace, "sshConfig").bufferedWriter().use { ClusterConfigWriter.writeSshConfig(it, "/path/to/key", hosts) }
        File(workspace, "env.sh").bufferedWriter().use { ClusterConfigWriter.writeEnvironmentFile(it, hosts, "lab") }
        ToolWrapperInstaller().install(workspace)
    }

    @Test
    fun `kubectl resolves to the workspace wrapper and none of the six tools is a function`() {
        val result =
            bash(
                "source ./env.sh >/dev/null; type -P kubectl; for t in $tools; do declare -F \"${'$'}t\" && echo \"function: ${'$'}t\"; done; true",
            )

        assertThat(result.stdout.lines().first()).isEqualTo(File(workspace, "bin/kubectl").canonicalPath)
        assertThat(result.stdout).doesNotContain("function:")
    }

    @Test
    fun `the old proxy functions and aliases are gone`() {
        val result =
            bash(
                "source ./env.sh >/dev/null; " +
                    "for f in start-socks5 stop-socks5; do declare -F \"${'$'}f\" && echo \"defined: ${'$'}f\"; done; " +
                    "for a in socks5-start socks5-stop; do alias \"${'$'}a\" 2>/dev/null && echo \"defined: ${'$'}a\"; done; true",
            )

        assertThat(result.stdout).doesNotContain("defined:")
    }

    @Test
    fun `re-sourcing removes the functions an older env sh defined`() {
        val oldFunctions = Constants.ToolWrappers.TOOLS.joinToString("\n") { "$it() { echo old-$it; }" }

        val result =
            bash(
                "$oldFunctions\nsource ./env.sh >/dev/null; for t in $tools; do declare -F \"${'$'}t\" && echo \"function: ${'$'}t\"; done; type -P helm",
            )

        assertThat(result.stdout).doesNotContain("function:")
        assertThat(
            result.stdout
                .trim()
                .lines()
                .last(),
        ).isEqualTo(File(workspace, "bin/helm").canonicalPath)
    }

    @Test
    fun `sourcing works under set -e, twice, and puts bin on PATH once`() {
        val result = bash("set -e\nsource ./env.sh >/dev/null\nsource ./env.sh >/dev/null\necho \"PATH=${'$'}PATH\"")

        assertThat(result.exitCode).withFailMessage(result.toString()).isZero()
        val path =
            result.stdout
                .lines()
                .single { it.startsWith("PATH=") }
                .removePrefix("PATH=")
                .split(":")
        assertThat(path.first()).isEqualTo(File(workspace, "bin").canonicalPath)
        assertThat(path.count { it == File(workspace, "bin").canonicalPath }).isEqualTo(1)
    }

    @Test
    fun `sourcing by a relative path with CDPATH set still finds this workspace`() {
        // `self` reaches this workspace through a relative path; CDPATH names a decoy that has a
        // `self` of its own, which a plain `cd self` would pick instead.
        Files.createSymbolicLink(File(workspace, "self").toPath(), workspace.toPath())
        val decoy = File(workspace, "decoy").apply { File(this, "self").mkdirs() }

        val result =
            ProcessBuilder("/bin/bash", "-c", "source self/env.sh >/dev/null; type -P kubectl")
                .directory(workspace)
                .also { it.environment()["CDPATH"] = decoy.absolutePath }
                .start()
        // The decoy has no sshConfig, so an env.sh that landed there would prompt for a key.
        result.outputStream.close()
        val stdout = result.inputStream.bufferedReader().readText()
        assertThat(result.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()

        assertThat(File(stdout.trim()).canonicalPath).isEqualTo(File(workspace, "bin/kubectl").canonicalPath)
    }

    @Test
    fun `with-proxy with no port recorded fails and names start-socks`() {
        ProxyEnvFile(workspace).recordTailscale(active = false)

        val result = bash("source ./env.sh >/dev/null; with-proxy echo ran")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.stdout).doesNotContain("ran")
        assertThat(result.stderr).contains("easy-db-lab start-socks")
    }

    @Test
    fun `with-proxy on a Tailscale cluster runs the command with no proxy variables added`() {
        ProxyEnvFile(workspace).recordTailscale(active = true)

        val result = bash("source ./env.sh >/dev/null; with-proxy sh -c 'echo \"ALL_PROXY=${'$'}{ALL_PROXY-unset}\"'")

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).contains("ALL_PROXY=unset")
    }

    @Test
    fun `with-proxy routes through the recorded port`() {
        ProxyEnvFile(workspace).apply {
            recordTailscale(active = false)
            recordPort(41234)
        }

        val result = bash("source ./env.sh >/dev/null; with-proxy sh -c 'echo \"ALL_PROXY=${'$'}ALL_PROXY\"'")

        assertThat(result.stdout).contains("ALL_PROXY=socks5h://localhost:41234")
    }

    @Test
    fun `socks5-status reports the recorded port`() {
        ProxyEnvFile(workspace).apply {
            recordTailscale(active = false)
            recordPort(41234)
        }

        val result = bash("source ./env.sh >/dev/null; socks5-status")

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).contains("41234")
    }

    private fun bash(script: String): Result {
        val process = ProcessBuilder("/bin/bash", "-c", script).directory(workspace).start()
        process.outputStream.close()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertThat(process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        return Result(process.exitValue(), stdout, stderr)
    }

    private data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private companion object {
        const val LIMIT_SECONDS = 30L
    }
}
