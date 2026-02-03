package com.rustyrazorblade.easydblab.configuration

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
     */
    fun writeSshConfig(
        writer: BufferedWriter,
        identityFile: String,
        hosts: Map<ServerType, List<ClusterHost>>,
    ) {
        // write standard stuff first
        writer.appendLine("StrictHostKeyChecking=no")
        writer.appendLine("User ubuntu")
        writer.appendLine("IdentityFile $identityFile")

        // get each server type and get the hosts for type and add it to the sshConfig.
        ServerType.entries.forEach { serverType ->
            hosts[serverType]?.forEach { host ->
                writer.appendLine("Host ${host.alias}")
                writer.appendLine(" Hostname ${host.publicIp}")
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
     */
    fun writeEnvironmentFile(
        writer: BufferedWriter,
        hosts: Map<ServerType, List<ClusterHost>>,
        clusterName: String,
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
        writer.appendLine("  identity_file=\$EASY_CASS_LAB_SSH_KEY")
        writer.appendLine("  if [ -z \$identity_file ]; then")
        writer.appendLine("    echo -n 'Path to Private key: '")
        writer.appendLine("    read -r identity_file")
        writer.appendLine(
            "    echo \"add \${YELLOW}'export EASY_CASS_LAB_SSH_KEY=\$identity_file'\${NC} " +
                "to .bash_profile, .zsh, or similar\"",
        )
        writer.appendLine("  fi")
        writer.appendLine("  echo \"Writing \$SSH_CONFIG\"")
        writer.appendLine("  tee \$SSH_CONFIG <<- EOF")
        writeSshConfig(writer, "\$identity_file", hosts)
        writer.appendLine("EOF")
        writer.appendLine("fi")
        writer.flush()
    }
}

/**
 * Converts ClusterHost to Host for compatibility with existing code.
 */
fun ClusterHost.toHost(): Host =
    Host(
        public = publicIp,
        private = privateIp,
        alias = alias,
        availabilityZone = availabilityZone,
    )

/**
 * Retrieves hosts as the legacy Host type for compatibility.
 */
fun ClusterState.getHosts(serverType: ServerType): List<Host> = hosts[serverType]?.map { it.toHost() } ?: emptyList()
