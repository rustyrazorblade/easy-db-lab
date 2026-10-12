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
 * The personal and system configs it includes are temp files with known contents, so the test does
 * not depend on the machine's own. Cluster hosts get the cluster settings and never read either file.
 * Every other host resolves exactly as it would without the workspace file: the same as a config that
 * only includes the personal file and then the system file, which is what `ssh` reads without `-F`.
 */
internal class GeneratedSshConfigResolutionTest {
    @TempDir
    lateinit var workspace: File

    private val sshConfig: File get() = File(workspace, "sshConfig")
    private val personal: File get() = File(workspace, "personal-config")
    private val system: File get() = File(workspace, "system-config")

    /** What `ssh` reads with no `-F`: the personal config, then the system config. */
    private val withoutWorkspaceFile: File get() = File(workspace, "no-workspace-config")

    @BeforeEach
    fun writeConfigs() {
        personal.writeText(
            """
            |Host github.com
            |  User git
            |Host *
            |  ControlMaster auto
            |  ControlPersist yes
            |  ProxyJump bastion
            |  ForwardAgent yes
            |  IdentityFile $PERSONAL_IDENTITY
            |
            """.trimMargin(),
        )
        system.writeText("Host *\n  SendEnv LANG LC_*\n  ServerAliveInterval 77\n")
        withoutWorkspaceFile.writeText("Include ${personal.absolutePath}\nInclude ${system.absolutePath}\n")
        val hosts =
            mapOf(
                ServerType.Control to listOf(ClusterHost("54.1.1.1", "10.0.0.1", "control0", "us-west-2a")),
                ServerType.Cassandra to listOf(ClusterHost("54.1.1.2", "10.0.0.2", "db0", "us-west-2a")),
                ServerType.Stress to listOf(ClusterHost("54.1.1.3", "10.0.0.3", "app0", "us-west-2a")),
            )
        sshConfig.bufferedWriter().use {
            ClusterConfigWriter.writeSshConfig(
                it,
                IDENTITY,
                hosts,
                proxyCommands = mapOf("control0" to "edl-ssm-proxy i-control"),
                personalConfig = personal.absolutePath,
                systemConfig = system.absolutePath,
            )
        }
    }

    @Test
    fun `every other host resolves exactly as it would without the workspace file`() {
        listOf("github.com", "192.0.2.10", "10.0.1.5", "db.example.com", "dbserver.example.com", "control.example.org")
            .forEach { host ->
                assertThat(resolve(sshConfig, host).sorted()).describedAs(host).isEqualTo(resolve(withoutWorkspaceFile, host).sorted())
            }
    }

    @Test
    fun `the personal settings reach github com and never a cluster host`() {
        assertThat(resolve(sshConfig, "github.com")).contains(*PERSONAL_ONLY)

        listOf("control0", "db0", "app0").forEach { host ->
            assertThat(resolve(sshConfig, host)).describedAs(host).doesNotContain(*PERSONAL_ONLY)
        }
    }

    @Test
    fun `the system settings never reach a cluster host`() {
        listOf("control0", "db0", "app0").forEach { host ->
            assertThat(
                resolve(sshConfig, host),
            ).describedAs(host).noneMatch { it.startsWith("sendenv ") }.doesNotContain("serveraliveinterval 77")
        }
    }

    @Test
    fun `every other host gets none of the cluster's options`() {
        listOf("github.com", "192.0.2.10", "10.0.1.5", "db.boo.com", "dbserver.example.com", "control.example.org").forEach { host ->
            assertThat(resolve(sshConfig, host)).describedAs(host).doesNotContain(*CLUSTER_ONLY)
        }
    }

    @Test
    fun `a database and an app host get the cluster options and their own address`() {
        listOf("db0" to "54.1.1.2", "app0" to "54.1.1.3").forEach { (alias, address) ->
            assertThat(resolve(sshConfig, alias)).describedAs(alias).contains(
                "hostname $address",
                *CLUSTER_ONLY,
                "stricthostkeychecking false",
                "serveraliveinterval ${Constants.Ssh.KEEPALIVE_INTERVAL_SECONDS}",
                "serveralivecountmax ${Constants.Ssh.KEEPALIVE_COUNT_MAX}",
                "connecttimeout ${Constants.Ssh.CONNECT_TIMEOUT_SECONDS}",
            )
        }
    }

    @Test
    fun `the control node gets the cluster options and its Session Manager ProxyCommand`() {
        assertThat(resolve(sshConfig, "control0")).contains("hostname 54.1.1.1", *CLUSTER_ONLY, "proxycommand edl-ssm-proxy i-control")
        assertThat(resolve(sshConfig, "db0")).doesNotContain("proxycommand edl-ssm-proxy i-control")
    }

    /** The options `ssh -G` resolves for [host] from [config], one `key value` line each. */
    private fun resolve(
        config: File,
        host: String,
    ): List<String> {
        val process = ProcessBuilder("ssh", "-F", config.absolutePath, "-G", host).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readLines()
        assertThat(process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).withFailMessage("ssh -G $host failed: $output").isZero()
        return output
    }

    private companion object {
        const val IDENTITY = "/path/to/cluster-identity"
        const val PERSONAL_IDENTITY = "/path/to/personal-identity"
        const val LIMIT_SECONDS = 30L

        /** The resolved options only the cluster block sets. */
        val CLUSTER_ONLY = arrayOf("identityfile $IDENTITY", "user ubuntu", "userknownhostsfile /dev/null")

        /** The resolved options only the personal config's `Host *` sets. */
        val PERSONAL_ONLY =
            arrayOf("controlmaster auto", "controlpersist yes", "proxyjump bastion", "forwardagent yes", "identityfile $PERSONAL_IDENTITY")
    }
}
