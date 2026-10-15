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

    private val tools = Constants.ToolWrappers.WRAPPED_TOOLS.joinToString(" ")

    @BeforeEach
    fun writeWorkspace() {
        val hosts =
            mapOf(
                ServerType.Control to listOf(ClusterHost("54.1.1.1", "10.0.0.1", "control0", "us-west-2a")),
                ServerType.Cassandra to listOf(ClusterHost("54.1.1.2", "10.0.0.2", "db0", "us-west-2a")),
            )
        File(workspace, "sshConfig").bufferedWriter().use { ClusterConfigWriter.writeSshConfig(it, "/path/to/key", hosts) }
        File(workspace, "env.sh").bufferedWriter().use { ClusterConfigWriter.writeEnvironmentFile(it, hosts, "lab", "lab") }
        ToolWrapperInstaller().install(workspace)
    }

    @Test
    fun `kubectl resolves to the workspace wrapper and none of the wrapped tools is a function`() {
        val result =
            bash(
                "source ./env.sh >/dev/null; type -P kubectl; for t in $tools; do declare -F \"${'$'}t\" && echo \"function: ${'$'}t\"; done; true",
            )

        assertThat(result.stdout.lines().first()).isEqualTo(File(workspace, "bin/kubectl").canonicalPath)
        assertThat(result.stdout).doesNotContain("function:")
    }

    @Test
    fun `ssh, scp, sftp and rsync resolve to the workspace wrappers`() {
        val result = bash("source ./env.sh >/dev/null; for t in ssh scp sftp rsync; do type -P \"${'$'}t\"; done")

        assertThat(result.stdout.trim().lines()).containsExactly(
            File(workspace, "bin/ssh").canonicalPath,
            File(workspace, "bin/scp").canonicalPath,
            File(workspace, "bin/sftp").canonicalPath,
            File(workspace, "bin/rsync").canonicalPath,
        )
    }

    @Test
    fun `c-all reaches each node through the ssh wrapper with the workspace sshConfig`() {
        // A stand-in for the real ssh, behind bin/ on PATH, that prints what it was given.
        val realBin =
            File(workspace, "real").apply {
                mkdirs()
                File(this, "ssh").apply {
                    writeText("#!/bin/sh\necho \"ssh-args: ${'$'}*\"\n")
                    setExecutable(true)
                }
            }

        val result = bash("PATH=\"${realBin.absolutePath}:${'$'}PATH\"; source ./env.sh >/dev/null; c-all hostname")

        assertThat(result.stdout.lines().filter { it.startsWith("ssh-args: ") })
            .containsExactly("ssh-args: -F ${File(workspace, "sshConfig").canonicalPath} db0 hostname")
    }

    @Test
    fun `with no sshConfig env sh writes one scoped to the cluster that includes the developer's own`() {
        File(workspace, "sshConfig").delete()

        bash("export EASY_DB_LAB_SSH_KEY=/keys/cluster; source ./env.sh >/dev/null")

        val written = File(workspace, "sshConfig").readLines()
        assertThat(written.first()).isEqualTo("Host db* app* control* !*.*")
        assertThat(written).contains(" IdentityFile /keys/cluster", "Host db0", " Hostname 54.1.1.2")
        // The heredoc leaves ~ as written, for ssh to expand.
        assertThat(written.dropLastWhile { it.isBlank() }.takeLast(3))
            .containsExactly("Match originalhost db*.*,app*.*,control*.*", " Include ~/.ssh/config", " Include /etc/ssh/ssh_config")
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
        val oldFunctions = Constants.ToolWrappers.WRAPPED_TOOLS.joinToString("\n") { "$it() { echo old-$it; }" }

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
    fun `with-proxy with no port recorded fails and names socks start`() {
        ProxyEnvFile(workspace).recordTailscale(active = false)

        val result = bash("source ./env.sh >/dev/null; with-proxy echo ran")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.stdout).doesNotContain("ran")
        assertThat(result.stderr).contains("easy-db-lab socks start").contains("/logs/socks5-proxy.log")
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
            recordTunnel(41234, ProcessHandle.current().pid())
        }

        val result =
            bash(
                "source ./env.sh >/dev/null; with-proxy sh -c " +
                    "'echo \"ALL_PROXY=${'$'}ALL_PROXY HTTP_PROXY=${'$'}HTTP_PROXY HTTPS_PROXY=${'$'}HTTPS_PROXY NO_PROXY=${'$'}NO_PROXY\"'",
            )

        assertThat(result.stdout).contains(
            "ALL_PROXY=socks5h://127.0.0.1:41234 HTTP_PROXY=socks5h://127.0.0.1:41234 " +
                "HTTPS_PROXY=socks5h://127.0.0.1:41234 NO_PROXY=localhost,127.0.0.1",
        )
    }

    @Test
    fun `socks5-status reports the recorded port`() {
        ProxyEnvFile(workspace).apply {
            recordTailscale(active = false)
            recordTunnel(41234, ProcessHandle.current().pid())
        }

        val result = bash("source ./env.sh >/dev/null; socks5-status")

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).contains("127.0.0.1:41234").doesNotContain("localhost")
    }

    @Test
    fun `with-proxy fails with the socks start message when the recorded tunnel script has exited`() {
        ProxyEnvFile(workspace).apply {
            recordTailscale(active = false)
            recordTunnel(41234, exitedPid())
        }

        val result = bash("source ./env.sh >/dev/null; with-proxy echo ran")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.stdout).doesNotContain("ran")
        assertThat(result.stderr).contains("easy-db-lab socks start").contains("/logs/socks5-proxy.log")
    }

    @Test
    fun `socks5-status says no tunnel runs when the recorded tunnel script has exited`() {
        ProxyEnvFile(workspace).apply {
            recordTailscale(active = false)
            recordTunnel(41234, exitedPid())
        }

        val result = bash("source ./env.sh >/dev/null; socks5-status")

        assertThat(result.stdout).doesNotContain("41234").contains("easy-db-lab socks start")
    }

    /** The PID of a process that has already exited. */
    private fun exitedPid(): Long =
        ProcessBuilder("true").start().let { process ->
            assertThat(process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            process.pid()
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
