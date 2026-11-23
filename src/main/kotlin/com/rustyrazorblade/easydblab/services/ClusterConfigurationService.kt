package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.AxonOpsWorkbenchConfig
import com.rustyrazorblade.easydblab.configuration.ClusterConfigWriter
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestinationAccess
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.SshRoute
import com.rustyrazorblade.easydblab.proxy.EnvScriptWriter
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.proxy.WorkspaceShellTools
import com.rustyrazorblade.easydblab.resolveInWorkspaceForWrite
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

/**
 * Service for writing cluster configuration files to the working directory.
 *
 * This service handles the creation of:
 * - SSH config file for connecting to cluster nodes
 * - Environment shell script with cluster aliases and metadata
 * - The tool wrappers in `bin/` and the network mode in the proxy env file, which `env.sh` and kit
 *   shell steps use to reach the cluster
 * - Stress environment variables for cassandra-easy-stress
 * - AxonOps Workbench configuration for GUI access
 */
interface ClusterConfigurationService {
    /**
     * Writes all configuration files to the working directory.
     *
     * @param workingDirectory Directory to write configuration files to
     * @param clusterState Current cluster state with host information
     * @return Result indicating success or failure with error details
     */
    fun writeAllConfigurationFiles(
        workingDirectory: Path,
        clusterState: ClusterState,
    ): Result<Unit>

    /**
     * Writes SSH config and environment files, the tool wrappers, and the cluster's network mode
     * in the proxy env file.
     *
     * @param workingDirectory Directory to write configuration files to
     * @param clusterState Current cluster state with host information
     */
    fun writeSshAndEnvironmentFiles(
        workingDirectory: Path,
        clusterState: ClusterState,
    )

    /**
     * Writes stress environment variables file.
     *
     * Creates environment.sh with:
     * - CASSANDRA_EASY_STRESS_CASSANDRA_HOST
     * - CASSANDRA_EASY_STRESS_PROM_PORT
     * - CASSANDRA_EASY_STRESS_DEFAULT_DC
     *
     * @param workingDirectory Directory to write configuration file to
     * @param clusterState Current cluster state with host and datacenter info; the profile's region
     *   is the datacenter when the state has none
     * @return Result indicating success or failure
     */
    fun writeStressEnvironmentVariables(
        workingDirectory: Path,
        clusterState: ClusterState,
    ): Result<Unit>

    /**
     * Writes AxonOps Workbench configuration file.
     *
     * @param workingDirectory Directory to write configuration file to
     * @param clusterState Current cluster state with host information
     * @return Result indicating success or failure
     */
    fun writeAxonOpsWorkbenchConfig(
        workingDirectory: Path,
        clusterState: ClusterState,
    ): Result<Unit>
}

/**
 * Default implementation of ClusterConfigurationService.
 *
 * @property profileStore the profile store, which names the profile's SSH key
 * @property awsDestination the profile's AWS destination, read when the stress file needs the region
 * @property sshRoute supplies each host's `ProxyCommand` when the profile's SSH transport needs one
 * @property envScript builds the `ProxyCommand`s for `sshConfig` and writes `env.sh`, so both route alike
 * @property workspaceShellTools writes the tool wrappers, `env.sh` and the network mode
 */
class DefaultClusterConfigurationService(
    private val profileStore: ProfileStore,
    private val awsDestination: AwsDestinationAccess,
    private val eventBus: EventBus,
    private val sshRoute: SshRoute,
    private val envScript: EnvScriptWriter = EnvScriptWriter(sshRoute),
    private val workspaceShellTools: WorkspaceShellTools = WorkspaceShellTools(ToolWrapperInstaller(), envScript),
) : ClusterConfigurationService {
    companion object {
        private val log = KotlinLogging.logger {}
        const val DEFAULT_CLUSTER_NAME = "easy-db-lab"
    }

    override fun writeAllConfigurationFiles(
        workingDirectory: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            writeSshAndEnvironmentFiles(workingDirectory, clusterState)
            writeStressEnvironmentVariables(workingDirectory, clusterState).getOrThrow()
            writeAxonOpsWorkbenchConfig(workingDirectory, clusterState).getOrThrow()
        }

    override fun writeSshAndEnvironmentFiles(
        workingDirectory: Path,
        clusterState: ClusterState,
    ) {
        val sshKeyPath = profileStore.sshKeyPath
        // Built before the file is opened, so a host that cannot be routed fails the write instead of
        // leaving a half-written sshConfig behind.
        val proxyCommands = envScript.proxyCommandsFor(clusterState.hosts)
        val sshConfigFile = resolveInWorkspaceForWrite(workingDirectory.toFile(), Constants.Workspace.SSH_CONFIG_FILE)
        sshConfigFile.bufferedWriter().use { writer ->
            ClusterConfigWriter.writeSshConfig(
                writer,
                sshKeyPath,
                clusterState.hosts,
                proxyCommands,
            )
        }

        // env.sh (its fallback ssh config routed as sshConfig is), the wrappers it puts first on PATH,
        // and the network mode they read. A cluster that needs no tunnel never starts one that would
        // record anything, so they are written here, where every cluster passes.
        workspaceShellTools.prepareWithEnvScript(workingDirectory.toFile(), clusterState, proxyCommands)
    }

    override fun writeStressEnvironmentVariables(
        workingDirectory: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            val cassandraHosts = clusterState.getHosts(ServerType.Cassandra)
            if (cassandraHosts.isEmpty()) {
                log.warn { "No Cassandra hosts found, skipping stress environment variables" }
                return@runCatching
            }

            val cassandraHost = cassandraHosts.first().private
            val datacenter = clusterState.initConfig?.region ?: awsDestination.current().location.region

            val stressEnvFile = resolveInWorkspaceForWrite(workingDirectory.toFile(), Constants.Workspace.STRESS_ENV_FILE)
            stressEnvFile.bufferedWriter().use { writer ->
                writer.write("#!/usr/bin/env bash")
                writer.newLine()
                writer.write("export CASSANDRA_EASY_STRESS_CASSANDRA_HOST=$cassandraHost")
                writer.newLine()
                writer.write("export CASSANDRA_EASY_STRESS_PROM_PORT=0")
                writer.newLine()
                writer.write("export CASSANDRA_EASY_STRESS_DEFAULT_DC=$datacenter")
                writer.newLine()
            }
        }

    override fun writeAxonOpsWorkbenchConfig(
        workingDirectory: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            val cassandraHosts = clusterState.getHosts(ServerType.Cassandra)
            if (cassandraHosts.isEmpty()) {
                log.warn { "No Cassandra hosts found, skipping AxonOps Workbench configuration" }
                return@runCatching
            }

            val cassandra0 = cassandraHosts.first()
            val config =
                AxonOpsWorkbenchConfig.create(
                    host = cassandra0,
                    sshKeyPath = profileStore.sshKeyPath,
                    clusterName = DEFAULT_CLUSTER_NAME,
                )
            val configFile = resolveInWorkspaceForWrite(workingDirectory.toFile(), Constants.Workspace.AXONOPS_CONFIG_FILE)
            AxonOpsWorkbenchConfig.writeToFile(config, configFile)
            eventBus.emit(Event.Provision.AxonOpsWorkbenchConfigWritten(Constants.Workspace.AXONOPS_CONFIG_FILE))
        }
}
