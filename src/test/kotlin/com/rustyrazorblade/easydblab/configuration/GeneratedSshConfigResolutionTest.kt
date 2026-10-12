package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The generated `sshConfig` as the real `ssh` resolves it, with `ssh -G`, which prints the options it
 * would use for a host and never connects.
 *
 * The cluster options apply to the cluster's hosts only. Every other host, such as `github.com` for a
 * `git` that runs through the workspace's `ssh` wrapper, gets what it would get without `-F`. `ssh`
 * reads the developer's own `~/.ssh/config` through the closing `Include`, so the checks for another
 * host name only the cluster's own values, which no personal config sets.
 */
internal class GeneratedSshConfigResolutionTest {
    @TempDir
    lateinit var workspace: File

    private val sshConfig: File get() = File(workspace, "sshConfig")

    @BeforeEach
    fun writeConfig() {
        val hosts =
            mapOf(
                ServerType.Control to listOf(ClusterHost("54.1.1.1", "10.0.0.1", "control0", "us-west-2a")),
                ServerType.Cassandra to listOf(ClusterHost("54.1.1.2", "10.0.0.2", "db0", "us-west-2a")),
                ServerType.Stress to listOf(ClusterHost("54.1.1.3", "10.0.0.3", "app0", "us-west-2a")),
            )
        sshConfig.bufferedWriter().use {
            ClusterConfigWriter.writeSshConfig(it, IDENTITY, hosts, proxyCommands = mapOf("control0" to "edl-ssm-proxy i-control"))
        }
    }

    @Test
    fun `another host gets none of the cluster's options`() {
        val resolved = resolve("github.com")

        assertThat(resolved).doesNotContain(*CLUSTER_ONLY)
        assertThat(resolved).contains("hostname github.com")
    }

    @Test
    fun `an IP address gets none of the cluster's options`() {
        listOf("192.0.2.10", "10.0.1.5").forEach { address ->
            assertThat(resolve(address)).describedAs(address).doesNotContain(*CLUSTER_ONLY)
        }
    }

    @Test
    fun `a dotted name that starts like a node type gets none of the cluster's options`() {
        // db* alone would match these; the cluster block's !*.* keeps every name with a dot out.
        listOf("db.boo.com", "dbserver.example.com", "control.example.org").forEach { host ->
            assertThat(resolve(host)).describedAs(host).doesNotContain(*CLUSTER_ONLY)
        }
    }

    @Test
    fun `a database and an app host get the cluster options and their own address`() {
        listOf("db0" to "54.1.1.2", "app0" to "54.1.1.3").forEach { (alias, address) ->
            val resolved = resolve(alias)

            assertThat(resolved).describedAs(alias).contains(
                "hostname $address",
                "user ubuntu",
                "identityfile $IDENTITY",
                "userknownhostsfile /dev/null",
                "stricthostkeychecking false",
                "serveraliveinterval ${Constants.Ssh.KEEPALIVE_INTERVAL_SECONDS}",
                "serveralivecountmax ${Constants.Ssh.KEEPALIVE_COUNT_MAX}",
                "connecttimeout ${Constants.Ssh.CONNECT_TIMEOUT_SECONDS}",
            )
        }
    }

    @Test
    fun `the control node gets the cluster options and its Session Manager ProxyCommand`() {
        val resolved = resolve("control0")

        assertThat(resolved).contains(
            "hostname 54.1.1.1",
            "user ubuntu",
            "identityfile $IDENTITY",
            "userknownhostsfile /dev/null",
            "proxycommand edl-ssm-proxy i-control",
        )
        assertThat(resolve("db0")).noneMatch { it.startsWith("proxycommand ") }
    }

    /** The options `ssh -G` resolves for [host] from the generated config, one `key value` line each. */
    private fun resolve(host: String): List<String> {
        val process = ProcessBuilder("ssh", "-F", sshConfig.absolutePath, "-G", host).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readLines()
        assertThat(process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).withFailMessage("ssh -G $host failed: $output").isZero()
        return output
    }

    private companion object {
        const val IDENTITY = "/path/to/cluster-identity"
        const val LIMIT_SECONDS = 30L

        /** The resolved options only the cluster block sets, which no personal ssh config would. */
        val CLUSTER_ONLY = arrayOf("identityfile $IDENTITY", "user ubuntu", "userknownhostsfile /dev/null")
    }
}
