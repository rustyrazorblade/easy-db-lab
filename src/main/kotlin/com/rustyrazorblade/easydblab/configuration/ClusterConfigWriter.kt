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
     * @param writer BufferedWriter to write SSH config to
     * @param identityFile Path to the SSH identity file
     * @param hosts Map of server types to their hosts
     * @param proxyCommands the `ProxyCommand` for each host alias that has one; a host without an
     *   entry is dialed at its public IP directly
     */
    fun writeSshConfig(
        writer: BufferedWriter,
        identityFile: String,
        hosts: Map<ServerType, List<ClusterHost>>,
        proxyCommands: Map<String, String> = emptyMap(),
    ) {
        // write standard stuff first
        writer.appendLine("StrictHostKeyChecking=no")
        // AWS recycles public IPs across ephemeral cluster lifetimes, so a host's key
        // recorded in the developer's ~/.ssh/known_hosts can collide with a different
        // cluster's key for the same IP. Pin to /dev/null so this config never reads
        // or writes that file -- matching the MINA SSHD path used everywhere else,
        // which verifies no host keys at all.
        writer.appendLine("UserKnownHostsFile=/dev/null")
        writer.appendLine("User ubuntu")
        writer.appendLine("IdentityFile $identityFile")
        if (proxyCommands.isNotEmpty()) {
            // Session Manager ends a session after 20 idle minutes, which silently kills a quiet
            // long-lived connection such as the SOCKS tunnel. Keepalives count as traffic, so
            // they hold the session open, and they make ssh exit promptly if it is dropped anyway.
            writer.appendLine("ServerAliveInterval ${Constants.Ssm.SSH_KEEPALIVE_INTERVAL_SECONDS}")
            writer.appendLine("ServerAliveCountMax ${Constants.Ssm.SSH_KEEPALIVE_COUNT_MAX}")
            // Keepalives start only after authentication, so a session whose plugin connects but
            // passes no data would hang ssh forever. ConnectTimeout bounds the wait for the server's
            // banner, ProxyCommand included (checked on OpenSSH 9.6 and 10.3).
            writer.appendLine("ConnectTimeout ${Constants.Ssm.SSH_CONNECT_TIMEOUT_SECONDS}")
        }

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

        writer.flush()
    }

    /**
     * Writes environment file content to the provided writer.
     *
     * @param writer BufferedWriter to write environment file to
     * @param hosts Map of server types to their hosts
     * @param clusterName Name of the cluster for prompt customization
     * @param proxyCommands the same per-host `ProxyCommand`s as the cluster's `sshConfig`, so the
     *   fallback config this file writes when `sshConfig` is missing routes hosts the same way
     */
    fun writeEnvironmentFile(
        writer: BufferedWriter,
        hosts: Map<ServerType, List<ClusterHost>>,
        clusterName: String,
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
