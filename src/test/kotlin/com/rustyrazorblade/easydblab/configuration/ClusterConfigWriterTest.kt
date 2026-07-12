package com.rustyrazorblade.easydblab.configuration

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
    private fun renderSshConfig(hosts: Map<ServerType, List<ClusterHost>>): String {
        val stringWriter = StringWriter()
        val bufferedWriter = BufferedWriter(stringWriter)
        ClusterConfigWriter.writeSshConfig(bufferedWriter, "/path/to/identity", hosts)
        return stringWriter.toString()
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
}
