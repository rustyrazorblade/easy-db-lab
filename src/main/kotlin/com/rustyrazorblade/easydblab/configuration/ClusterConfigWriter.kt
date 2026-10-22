package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import java.io.BufferedWriter

/**
 * Helper for writing cluster configuration files based on ClusterState.
 *
 * Provides functionality to generate SSH config and environment files
 * using host information stored in ClusterState.
 */
object ClusterConfigWriter {
    /**
     * Writes SSH config content to the provided writer.
     *
     * Every cluster-wide option sits in one `Host` block whose patterns are a wildcard per
     * [ServerType] prefix (`db*`, `app*`, `control*`) and the negated `!*.*`, so they apply to every
     * cluster host, however many the cluster grows to, and to no other host. OpenSSH patterns have no
     * character classes, so the wildcards alone would also match a name such as `db.boo.com`; the
     * negation keeps every name with a dot, and so every IP address, out of the block. Each host then
     * gets its own block with its address and, under the `ssm` transport, its `ProxyCommand`.
     *
     * Cluster hosts never read the personal or the system config, so none of their options (such as
     * `ControlMaster`, `ProxyJump`, `ForwardAgent` or another `IdentityFile`) reaches a node or the
     * SOCKS tunnel. Every other host, such as `github.com` for a `git` that runs through the
     * workspace's `ssh` wrapper, includes [personalConfig] and then [systemConfig], which is what
     * `ssh` reads without `-F`, so it resolves exactly as it would without the workspace file. Two
     * `Match originalhost` blocks select those names: one without a cluster prefix, and one with a
     * prefix and a dot. They are disjoint because options such as `IdentityFile` add up, and a name
     * that included the files twice would get them twice. `originalhost` is the name as typed,
     * before a `Hostname` replaces it. A missing included file is no error.
     *
     * @param writer BufferedWriter to write SSH config to
     * @param identityFile Path to the SSH identity file
     * @param hosts Map of server types to their hosts
     * @param proxyCommands the `ProxyCommand` for each host alias that has one; a host without an
     *   entry is dialed at its public IP directly
     * @param personalConfig the personal OpenSSH config every other host includes first
     * @param systemConfig the system OpenSSH config every other host includes after [personalConfig]
     */
    fun writeSshConfig(
        writer: BufferedWriter,
        identityFile: String,
        hosts: Map<ServerType, List<ClusterHost>>,
        proxyCommands: Map<String, String> = emptyMap(),
        personalConfig: String = Constants.Ssh.PERSONAL_CONFIG,
        systemConfig: String = Constants.Ssh.SYSTEM_CONFIG,
    ) {
        val prefixes = ServerType.entries.map { it.serverType }
        // !*.* keeps every name with a dot, and so every IP address, out of the cluster block: OpenSSH
        // patterns have no character classes, so db* alone would also match db.boo.com.
        writer.appendLine("Host ${prefixes.joinToString(" ") { "$it*" }} !*.*")
        writer.appendLine(" StrictHostKeyChecking=no")
        // AWS recycles public IPs across ephemeral cluster lifetimes, so a host's key
        // recorded in the developer's ~/.ssh/known_hosts can collide with a different
        // cluster's key for the same IP. Pin to /dev/null so this config never reads
        // or writes that file -- matching the MINA SSHD path used everywhere else,
        // which verifies no host keys at all.
        writer.appendLine(" UserKnownHostsFile=/dev/null")
        writer.appendLine(" User ubuntu")
        writer.appendLine(" IdentityFile $identityFile")
        // Every transport needs keepalives. An idle connection such as the SOCKS tunnel can die
        // silently, after a laptop sleep or a network change on a direct connection, or when Session
        // Manager ends a session after 20 idle minutes. Without keepalives ssh lingers with its local
        // port open and the tunnel carries nothing. Keepalives count as traffic, so they hold an SSM
        // session open, and they make ssh exit within interval x count when the connection is gone.
        writer.appendLine(" ServerAliveInterval ${Constants.Ssh.KEEPALIVE_INTERVAL_SECONDS}")
        writer.appendLine(" ServerAliveCountMax ${Constants.Ssh.KEEPALIVE_COUNT_MAX}")
        // Keepalives start only after authentication, so a connect that never completes (a host that
        // drops the SYN, or a Session Manager plugin that connects but passes no data) would hang
        // ssh, and the tunnel script with it. ConnectTimeout bounds the wait for the server's banner,
        // ProxyCommand included (checked on OpenSSH 9.6 and 10.3).
        writer.appendLine(" ConnectTimeout ${Constants.Ssh.CONNECT_TIMEOUT_SECONDS}")
        writer.appendLine()

        // get each server type and get the hosts for type and add it to the sshConfig.
        ServerType.entries.forEach { serverType ->
            hosts[serverType]?.forEach { host ->
                writer.appendLine("Host ${host.alias}")
                writer.appendLine(" Hostname ${host.publicIp}")
                // After Hostname, never before it: env.sh reads Hostname with `grep -A 1 "^Host <alias>"`.
                proxyCommands[host.alias]?.let { writer.appendLine(" ProxyCommand $it") }
                writer.appendLine()
            }
        }

        // Every other host resolves as it would without -F: a name without a cluster prefix, and a
        // dotted name with one. Disjoint, so no name includes the files twice.
        listOf(
            "!originalhost ${prefixes.joinToString(",") { "$it*" }}",
            "originalhost ${prefixes.joinToString(",") { "$it*.*" }}",
        ).forEach { criteria ->
            writer.appendLine("Match $criteria")
            writer.appendLine(" Include $personalConfig")
            writer.appendLine(" Include $systemConfig")
        }

        writer.flush()
    }

    /**
     * Writes environment file content to the provided writer.
     *
     * @param writer BufferedWriter to write environment file to
     * @param hosts Map of server types to their hosts
     * @param clusterName Name of the cluster for prompt customization
     * @param experiment the cluster's experiment, written as `EXPERIMENT`
     * @param proxyCommands the same per-host `ProxyCommand`s as the cluster's `sshConfig`, so the
     *   fallback config this file writes when `sshConfig` is missing routes hosts the same way
     */
    fun writeEnvironmentFile(
        writer: BufferedWriter,
        hosts: Map<ServerType, List<ClusterHost>>,
        clusterName: String,
        experiment: String,
        proxyCommands: Map<String, String> = emptyMap(),
    ) {
        // write the initial SSH aliases
        writer.appendLine("#!/bin/bash")
        writer.appendLine()

        var i = 0
        writer.append("SERVERS=(")
        hosts[ServerType.Cassandra]?.forEach { _ ->
            writer.append("db$i ")
            i++
        }
        writer.appendLine(")")

        // Cluster metadata for prompt customization
        writer.appendLine("CLUSTER_NAME=\"$clusterName\"")
        writer.appendLine("EXPERIMENT=\"$experiment\"")
        writer.appendLine("DB_NODE_COUNT=${hosts[ServerType.Cassandra]?.size ?: 0}")
        writer.appendLine("APP_NODE_COUNT=${hosts[ServerType.Stress]?.size ?: 0}")

        // Container registry URL for jib
        hosts[ServerType.Control]?.firstOrNull()?.let { controlHost ->
            writer.appendLine("export EDL_CONTAINER_REGISTRY=\"${controlHost.privateIp}:5000\"")
        }
        writer.appendLine()

        i = 0
        hosts[ServerType.Cassandra]?.forEach { _ ->
            writer.appendLine("alias c$i=\"ssh db${i}\"")
            i++
        }

        i = 0
        hosts[ServerType.Stress]?.forEach { _ ->
            writer.appendLine("alias s$i=\"ssh app${i}\"")
            i++
        }

        writer.appendLine()

        // Read env.sh template from resources
        val content =
            ClusterConfigWriter::class.java
                .getResourceAsStream("/com/rustyrazorblade/easydblab/configuration/env.sh")
                ?.bufferedReader()
        content?.readLines()?.forEach(writer::appendLine)

        // write out bash that generates ssh config for sharing the cluster
        // this is meant for folks not using easy-db-lab who need access
        writer.appendLine("")
        writer.appendLine("if ! [ -f \$SSH_CONFIG ]; then ")
        writer.appendLine("  echo \"\$SSH_CONFIG does not exist. Setting it up...\"")
        writer.appendLine("  identity_file=\$EASY_DB_LAB_SSH_KEY")
        writer.appendLine("  if [ -z \$identity_file ]; then")
        writer.appendLine("    echo -n 'Path to Private key: '")
        writer.appendLine("    read -r identity_file")
        writer.appendLine(
            "    echo \"add \${YELLOW}'export EASY_DB_LAB_SSH_KEY=\$identity_file'\${NC} " +
                "to .bash_profile, .zsh, or similar\"",
        )
        writer.appendLine("  fi")
        writer.appendLine("  echo \"Writing \$SSH_CONFIG\"")
        writer.appendLine("  tee \$SSH_CONFIG <<- EOF")
        writeSshConfig(writer, "\$identity_file", hosts, proxyCommands)
        writer.appendLine("EOF")
        writer.appendLine("fi")
        writer.flush()
    }
}
