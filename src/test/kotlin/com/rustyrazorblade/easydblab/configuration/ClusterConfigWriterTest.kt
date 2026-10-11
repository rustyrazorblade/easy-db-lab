package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.BufferedWriter
import java.io.StringWriter

/**
 * Tests for [ClusterConfigWriter], focused on the generated SSH config's
 * host-key verification directives and per-host structure.
 *
 * See openspec/changes/up-fail-fast/specs/networking/spec.md, requirement
 * "Generated SSH configuration is self-contained": the generated config must
 * fully determine host-key verification and must not depend on the developer's
 * `~/.ssh/known_hosts`, because AWS recycles public IPs across ephemeral
 * cluster lifetimes.
 */
internal class ClusterConfigWriterTest {
    private fun renderSshConfig(
        hosts: Map<ServerType, List<ClusterHost>>,
        proxyCommands: Map<String, String> = emptyMap(),
    ): String {
        val stringWriter = StringWriter()
        val bufferedWriter = BufferedWriter(stringWriter)
        ClusterConfigWriter.writeSshConfig(bufferedWriter, "/path/to/identity", hosts, proxyCommands)
        return stringWriter.toString()
    }

    private val control = ClusterHost("54.1.1.1", "10.0.0.1", "control0", "us-west-2a")

    /** The global lines of [config], before its first Host block, where an option applies to every host. */
    private fun globalLines(config: String): List<String> = config.lines().takeWhile { !it.startsWith("Host ") }

    @Test
    fun `a direct config sends keepalives to every host, so a tunnel whose connection died makes ssh exit`() {
        val global = globalLines(renderSshConfig(mapOf(ServerType.Control to listOf(control))))

        assertThat(global).contains(
            "ServerAliveInterval ${Constants.Ssh.KEEPALIVE_INTERVAL_SECONDS}",
            "ServerAliveCountMax ${Constants.Ssh.KEEPALIVE_COUNT_MAX}",
        )
        assertThat(global).noneMatch { it.startsWith("ConnectTimeout") }
    }

    @Test
    fun `an ssm config sends the same keepalives and bounds the wait for the banner`() {
        val config = renderSshConfig(mapOf(ServerType.Control to listOf(control)), mapOf("control0" to "edl-ssm-proxy i-control"))

        assertThat(globalLines(config)).contains(
            "ServerAliveInterval ${Constants.Ssh.KEEPALIVE_INTERVAL_SECONDS}",
            "ServerAliveCountMax ${Constants.Ssh.KEEPALIVE_COUNT_MAX}",
            "ConnectTimeout ${Constants.Ssm.SSH_CONNECT_TIMEOUT_SECONDS}",
        )
    }

    @Test
    fun `writeSshConfig pins UserKnownHostsFile to dev null alongside StrictHostKeyChecking before any Host block`() {
        val controlHost =
            ClusterHost(
                publicIp = "54.1.1.1",
                privateIp = "10.0.0.1",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        val config = renderSshConfig(mapOf(ServerType.Control to listOf(controlHost)))

        assertThat(config).contains("StrictHostKeyChecking=no")
        assertThat(config).contains("UserKnownHostsFile=/dev/null")

        val strictHostKeyCheckingIndex = config.indexOf("StrictHostKeyChecking=no")
        val userKnownHostsFileIndex = config.indexOf("UserKnownHostsFile=/dev/null")
        val firstHostBlockIndex = config.indexOf("Host control0")
        assertThat(firstHostBlockIndex).isGreaterThanOrEqualTo(0)

        // Both directives are global (outside any Host block), so they must precede
        // the first "Host <alias>" line -- ssh_config applies later Host-scoped
        // settings only within that block, but global settings must come first to
        // apply to every host, including hosts with recycled public IPs.
        assertThat(strictHostKeyCheckingIndex).isLessThan(firstHostBlockIndex)
        assertThat(userKnownHostsFileIndex).isLessThan(firstHostBlockIndex)
    }

    @Test
    fun `writeSshConfig emits one Host alias and Hostname pair per host across multiple server types`() {
        val controlHost =
            ClusterHost(
                publicIp = "54.1.1.1",
                privateIp = "10.0.0.1",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        val dbHosts =
            listOf(
                ClusterHost(
                    publicIp = "54.1.1.2",
                    privateIp = "10.0.0.2",
                    alias = "db0",
                    availabilityZone = "us-west-2a",
                ),
                ClusterHost(
                    publicIp = "54.1.1.3",
                    privateIp = "10.0.0.3",
                    alias = "db1",
                    availabilityZone = "us-west-2b",
                ),
            )
        val hosts =
            mapOf(
                ServerType.Control to listOf(controlHost),
                ServerType.Cassandra to dbHosts,
            )

        val config = renderSshConfig(hosts)

        val expectedPairs =
            listOf(
                "control0" to "54.1.1.1",
                "db0" to "54.1.1.2",
                "db1" to "54.1.1.3",
            )

        expectedPairs.forEach { (alias, publicIp) ->
            assertThat(config).contains("Host $alias")
            assertThat(config).contains("Hostname $publicIp")
        }

        // Exactly one Host block per host -- no duplicates, no missing entries.
        val hostBlockCount = Regex("(?m)^Host ").findAll(config).count()
        assertThat(hostBlockCount).isEqualTo(expectedPairs.size)
    }

    @Test
    fun `env sh reads proxy state only from the env file and puts the workspace bin first on PATH`() {
        val stringWriter = StringWriter()
        BufferedWriter(stringWriter).use { writer ->
            ClusterConfigWriter.writeEnvironmentFile(
                writer,
                mapOf(ServerType.Control to listOf(ClusterHost("54.1.1.1", "10.0.0.1", "control0", "us-west-2a"))),
                "lab",
            )
        }
        val envSh = stringWriter.toString()

        assertThat(envSh).doesNotContain("jq").doesNotContain(".socks5-proxy-state")
        assertThat(envSh).contains("export PATH=\"\$CLUSTER_DIR/bin:\$PATH\"")
        assertThat(envSh).contains("\$CLUSTER_DIR/.socks5-proxy.env")
        assertThat(envSh).doesNotContainPattern("(?m)^(kubectl|helm|cilium|curl|skopeo|k9s)\\(\\)")
    }
}
