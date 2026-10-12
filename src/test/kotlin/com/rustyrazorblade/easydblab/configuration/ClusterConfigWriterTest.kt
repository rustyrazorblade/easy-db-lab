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

    /** The line that opens the block of options every cluster host gets, one wildcard per server type. */
    private val clusterPattern = "Host db* app* control*"

    /** The option lines of [config]'s cluster block: after [clusterPattern], up to the next blank line. */
    private fun clusterBlock(config: String): List<String> =
        config
            .lines()
            .dropWhile { it != clusterPattern }
            .drop(1)
            .takeWhile { it.isNotBlank() }
            .map { it.trim() }

    /** The cluster-wide keepalive and connect-timeout lines every transport's config carries. */
    private val connectionBounds =
        listOf(
            "ServerAliveInterval ${Constants.Ssh.KEEPALIVE_INTERVAL_SECONDS}",
            "ServerAliveCountMax ${Constants.Ssh.KEEPALIVE_COUNT_MAX}",
            "ConnectTimeout ${Constants.Ssh.CONNECT_TIMEOUT_SECONDS}",
        )

    @Test
    fun `the config opens with one block for every cluster host and sets nothing outside a Host block`() {
        val config = renderSshConfig(mapOf(ServerType.Control to listOf(control)))

        assertThat(config.lines().first()).isEqualTo(clusterPattern)
        assertThat(clusterBlock(config)).containsExactly(
            "StrictHostKeyChecking=no",
            "UserKnownHostsFile=/dev/null",
            "User ubuntu",
            "IdentityFile /path/to/identity",
            *connectionBounds.toTypedArray(),
        )
    }

    @Test
    fun `the cluster block names one wildcard for each server type`() {
        val patterns =
            renderSshConfig(emptyMap())
                .lines()
                .first()
                .removePrefix("Host ")
                .split(" ")

        assertThat(patterns).containsExactlyElementsOf(ServerType.entries.map { "${it.serverType}*" })
    }

    @Test
    fun `the config ends by including the developer's own ssh config for every host`() {
        val config = renderSshConfig(mapOf(ServerType.Control to listOf(control)))

        // Host * first: an Include inside the last host's block would apply to that host only.
        assertThat(config.trimEnd().lines().takeLast(2)).containsExactly("Host *", "Include ~/.ssh/config")
    }

    @Test
    fun `an ssm config carries the same cluster options and a ProxyCommand only in its host's block`() {
        val config = renderSshConfig(mapOf(ServerType.Control to listOf(control)), mapOf("control0" to "edl-ssm-proxy i-control"))

        assertThat(clusterBlock(config)).containsAll(connectionBounds)
        val hostBlock =
            config
                .lines()
                .dropWhile { it != "Host control0" }
                .drop(1)
                .takeWhile { it.isNotBlank() }
        assertThat(hostBlock).containsExactly(" Hostname 54.1.1.1", " ProxyCommand edl-ssm-proxy i-control")
    }

    @Test
    fun `writeSshConfig emits one Host alias and Hostname pair per host across multiple server types`() {
        val hosts =
            mapOf(
                ServerType.Control to listOf(control),
                ServerType.Cassandra to
                    listOf(
                        ClusterHost(publicIp = "54.1.1.2", privateIp = "10.0.0.2", alias = "db0", availabilityZone = "us-west-2a"),
                        ClusterHost(publicIp = "54.1.1.3", privateIp = "10.0.0.3", alias = "db1", availabilityZone = "us-west-2b"),
                    ),
            )

        val config = renderSshConfig(hosts)

        val expectedPairs = listOf("control0" to "54.1.1.1", "db0" to "54.1.1.2", "db1" to "54.1.1.3")
        expectedPairs.forEach { (alias, publicIp) ->
            // env.sh's fallback reads a host's address with `grep -A 1 "^Host <alias>"`.
            assertThat(config).contains("Host $alias\n Hostname $publicIp\n")
        }
        // Exactly one block per host, besides the cluster block and the closing Host *.
        val hostAliases = Regex("(?m)^Host ([a-z]+\\d+)$").findAll(config).map { it.groupValues[1] }.toList()
        assertThat(hostAliases).containsExactly("db0", "db1", "control0")
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
