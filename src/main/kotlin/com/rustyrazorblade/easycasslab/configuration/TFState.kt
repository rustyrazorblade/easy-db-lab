@file:Suppress("ConstructorParameterNaming")

package com.rustyrazorblade.easycasslab.configuration

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.commands.mixins.HostsMixin
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

typealias HostList = List<Host>

/**
 * Represents EMR cluster information extracted from Terraform state.
 *
 * @property clusterId The unique EMR cluster ID (e.g., "j-ABC123XYZ")
 * @property name The cluster name as defined in Terraform
 * @property masterPublicDns The public DNS name of the EMR master node
 * @property state The current state of the cluster (e.g., "WAITING", "RUNNING", "TERMINATED")
 */
data class EMRClusterInfo(
    val clusterId: String,
    val name: String,
    val masterPublicDns: String?,
    val state: String?,
)

class TFState(
    val context: Context,
    val file: InputStream,
) {
    @JsonIgnoreProperties
    data class StateFile(
        val resources: List<Resource>,
    )

    data class Resource(
        val mode: String,
        val type: String? = null,
        val name: String,
        val instances: List<Instance>,
    )

    data class Instance(
        val attributes: Map<String, Any?>,
    ) {
        fun getName(): String {
            @Suppress("UNCHECKED_CAST")
            val tags = attributes["tags"] as? Map<String, String>
            return tags?.get("Name") ?: error("Instance has no 'Name' tag")
        }
    }

    private var log = KotlinLogging.logger {}

    companion object {
        fun parse(
            context: Context,
            path: File,
        ): TFState = TFState(context, path.inputStream())
    }

    // we put this here for two reasons
    // the first is we don't want to process it more than once
    // the second is we don't want to process if we don't read the state
    val state by lazy {
        log.info { "Loading tfstate from $file" }
        val json = context.getJsonMapper()
        json.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val state: StateFile = json.readValue(file)
        state
    }

    fun getHosts(serverType: ServerType): HostList {
        val instances =
            state.resources
                .filter { it.name.startsWith(serverType.serverType) }
                .flatMap { it.instances }
        log.info { "Matching $serverType to $instances" }

        val hosts =
            instances.mapIndexed { index, instance ->
                val attrs = instance.attributes
                Host(
                    public = attrs["public_ip"] as? String ?: "",
                    private = attrs["private_ip"] as? String ?: "",
                    alias = instance.getName(),
                    availabilityZone = attrs["availability_zone"] as? String ?: "",
                )
            } ?: listOf()

        return hosts
    }

    /**
     * Retrieves the VPC ID from Terraform state.
     *
     * @return VPC ID if a VPC exists in the state, null otherwise
     */
    fun getVpcId(): String? {
        val resource =
            state.resources
                .firstOrNull { it.type == "aws_vpc" }
                ?: return null

        val instance = resource.instances.firstOrNull() ?: return null
        return instance.attributes["id"] as? String
    }

    /**
     * Retrieves EMR cluster information from Terraform state.
     *
     * @return EMRClusterInfo if an EMR cluster exists in the state, null otherwise
     */
    fun getEMRCluster(): EMRClusterInfo? {
        val resource =
            state.resources
                .firstOrNull { it.type == "aws_emr_cluster" && it.name == "cluster" }
                ?: return null

        val instance = resource.instances.firstOrNull() ?: return null
        val attrs = instance.attributes

        val clusterId = attrs["id"] as? String ?: return null
        val name = attrs["name"] as? String ?: "cluster"
        val masterPublicDns = attrs["master_public_dns"] as? String
        val clusterState = attrs["cluster_state"] as? String

        return EMRClusterInfo(
            clusterId = clusterId,
            name = name,
            masterPublicDns = masterPublicDns,
            state = clusterState,
        )
    }

    /**
     * Get all hosts as a map keyed by ServerType
     * Returns Map<ServerType, List<ClusterHost>> suitable for storing in ClusterState
     */
    fun getAllHostsAsMap(): Map<ServerType, List<ClusterHost>> =
        ServerType.values().associate { serverType ->
            serverType to
                getHosts(serverType).map { host ->
                    ClusterHost(
                        publicIp = host.public,
                        privateIp = host.private,
                        alias = host.alias,
                        availabilityZone = host.availabilityZone,
                    )
                }
        }

    /**
     * Host filter is a simple string check for now.
     * @param parallel If true, execute operations on each host in parallel threads
     */
    fun withHosts(
        serverType: ServerType,
        hostFilter: HostsMixin,
        parallel: Boolean = false,
        withHost: (h: Host) -> Unit,
    ) {
        withHostsInternal(serverType, hostFilter.hostList, parallel, withHost)
    }

    private fun withHostsInternal(
        serverType: ServerType,
        hostList: String,
        parallel: Boolean,
        withHost: (h: Host) -> Unit,
    ) {
        val hostSet =
            hostList
                .split(",")
                .filter { it.isNotBlank() }
                .toSet()
        val hosts =
            getHosts(serverType).filter {
                hostSet.isEmpty() || it.alias in hostSet
            }

        if (parallel && hosts.size > 1) {
            val threads =
                hosts.map { host ->
                    thread(start = true, isDaemon = false) {
                        withHost(host)
                    }
                }
            threads.forEach { it.join() }
        } else {
            hosts.forEach(withHost)
        }
    }

    fun writeSshConfig(
        config: BufferedWriter,
        identityFile: String,
    ) {
        // write standard stuff first
        config.appendLine("StrictHostKeyChecking=no")
        config.appendLine("User ubuntu")
        config.appendLine("IdentityFile $identityFile")

        // get each server type and get the hosts for type and add it to the sshConfig.
        ServerType.values().forEach {
            getHosts(it).forEach {
                config.appendLine("Host ${it.alias}")
                config.appendLine(" Hostname ${it.public}")
                config.appendLine()
            }
        }
        config.flush()
    }

    fun writeEnvironmentFile(fp: BufferedWriter) {
        // write the initial SSH aliases
        fp.appendLine("#!/bin/bash")
        fp.appendLine()

        var i = 0
        fp.append("SERVERS=(")
        getHosts(ServerType.Cassandra).forEach {
            fp.append("cassandra$i ")
            i++
        }
        fp.appendLine(")")

        i = 0
        getHosts(ServerType.Cassandra).forEach {
            fp.appendLine("alias c$i=\"ssh cassandra${i}\"")
            i++
        }

        i = 0
        getHosts(ServerType.Stress).forEach {
            fp.appendLine("alias s$i=\"ssh stress${i}\"")
            i++
        }

        fp.appendLine()

        val content = this.javaClass.getResourceAsStream("env.sh").bufferedReader()
        content.readLines().toMutableList().forEach(fp::appendLine)

        // write out bash that generates ssh config for sharing the cluster
        // this is meant for folks not using easy-cass-lab who need access
        fp.appendLine("")
        fp.appendLine("if ! [ -f \$SSH_CONFIG ]; then ")
        fp.appendLine("  echo \"\$SSH_CONFIG does not exist. Setting it up...\"")
        fp.appendLine("  identity_file=\$EASY_CASS_LAB_SSH_KEY")
        fp.appendLine("  if [ -z \$identity_file ]; then")
        fp.appendLine("    echo -n 'Path to Private key: '")
        fp.appendLine("    read -r identity_file")
        fp.appendLine(
            "    echo \"add \${YELLOW}'export EASY_CASS_LAB_SSH_KEY=\$identity_file'\${NC} " +
                "to .bash_profile, .zsh, or similar\"",
        )
        fp.appendLine("  fi")
        fp.appendLine("  echo \"Writing \$SSH_CONFIG\"")
        fp.appendLine("  tee \$SSH_CONFIG <<- EOF")
        writeSshConfig(fp, "\$identity_file")
        fp.appendLine("EOF")
        fp.appendLine("fi")
        fp.flush()
    }
}
