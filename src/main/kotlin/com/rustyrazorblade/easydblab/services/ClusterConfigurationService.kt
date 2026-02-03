package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.AxonOpsWorkbenchConfig
import com.rustyrazorblade.easydblab.configuration.ClusterConfigWriter
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.configuration.getHosts
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path

/**
 * Service for writing cluster configuration files to the working directory.
 *
 * This service handles the creation of:
 * - SSH config file for connecting to cluster nodes
 * - Environment shell script with cluster aliases and metadata
 * - Stress environment variables for cassandra-easy-stress
 * - AxonOps Workbench configuration for GUI access
 */
interface ClusterConfigurationService {
    /**
     * Writes all configuration files to the working directory.
     *
     * @param workingDirectory Directory to write configuration files to
     * @param clusterState Current cluster state with host information
     * @param userConfig User configuration with SSH key path and region
     * @return Result indicating success or failure with error details
     */
    fun writeAllConfigurationFiles(
        workingDirectory: Path,
        clusterState: ClusterState,
        userConfig: User,
    ): Result<Unit>

    /**
     * Writes SSH config and environment files.
     *
     * @param workingDirectory Directory to write configuration files to
     * @param clusterState Current cluster state with host information
     * @param userConfig User configuration with SSH key path
     */
    fun writeSshAndEnvironmentFiles(
        workingDirectory: Path,
        clusterState: ClusterState,
        userConfig: User,
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
     * @param clusterState Current cluster state with host and datacenter info
     * @param userConfig User configuration with fallback region
     * @return Result indicating success or failure
     */
    fun writeStressEnvironmentVariables(
        workingDirectory: Path,
        clusterState: ClusterState,
        userConfig: User,
    ): Result<Unit>

    /**
     * Writes AxonOps Workbench configuration file.
     *
     * @param workingDirectory Directory to write configuration file to
     * @param clusterState Current cluster state with host information
     * @param userConfig User configuration with SSH key path
     * @return Result indicating success or failure
     */
    fun writeAxonOpsWorkbenchConfig(
        workingDirectory: Path,
        clusterState: ClusterState,
        userConfig: User,
    ): Result<Unit>
}

/**
 * Default implementation of ClusterConfigurationService.
 *
 * @property outputHandler Handler for user-facing messages
 * @property userConfigProvider Provider for user configuration including SSH key path
 */
class DefaultClusterConfigurationService(
    private val outputHandler: OutputHandler,
    private val userConfigProvider: UserConfigProvider,
) : ClusterConfigurationService {
    companion object {
        private val log = KotlinLogging.logger {}
        const val SSH_CONFIG_FILE = "sshConfig"
        const val ENV_FILE = "env.sh"
        const val STRESS_ENV_FILE = "environment.sh"
        const val AXONOPS_CONFIG_FILE = "axonops-workbench.json"
        const val DEFAULT_CLUSTER_NAME = "easy-db-lab"
    }

    override fun writeAllConfigurationFiles(
        workingDirectory: Path,
        clusterState: ClusterState,
        userConfig: User,
    ): Result<Unit> =
        runCatching {
            writeSshAndEnvironmentFiles(workingDirectory, clusterState, userConfig)
            writeStressEnvironmentVariables(workingDirectory, clusterState, userConfig).getOrThrow()
            writeAxonOpsWorkbenchConfig(workingDirectory, clusterState, userConfig).getOrThrow()
        }

    override fun writeSshAndEnvironmentFiles(
        workingDirectory: Path,
        clusterState: ClusterState,
        userConfig: User,
    ) {
        val sshKeyPath = userConfigProvider.sshKeyPath
        val sshConfigFile = File(workingDirectory.toFile(), SSH_CONFIG_FILE)
        sshConfigFile.bufferedWriter().use { writer ->
            ClusterConfigWriter.writeSshConfig(writer, sshKeyPath, clusterState.hosts)
        }

        val envFile = File(workingDirectory.toFile(), ENV_FILE)
        envFile.bufferedWriter().use { writer ->
            ClusterConfigWriter.writeEnvironmentFile(
                writer,
                clusterState.hosts,
                clusterState.name,
            )
        }
    }

    override fun writeStressEnvironmentVariables(
        workingDirectory: Path,
        clusterState: ClusterState,
        userConfig: User,
    ): Result<Unit> =
        runCatching {
            val cassandraHosts = clusterState.getHosts(ServerType.Cassandra)
            if (cassandraHosts.isEmpty()) {
                log.warn { "No Cassandra hosts found, skipping stress environment variables" }
                return@runCatching
            }

            val cassandraHost = cassandraHosts.first().private
            val datacenter = clusterState.initConfig?.region ?: userConfig.region

            val stressEnvFile = File(workingDirectory.toFile(), STRESS_ENV_FILE)
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

    @Suppress("TooGenericExceptionCaught")
    override fun writeAxonOpsWorkbenchConfig(
        workingDirectory: Path,
        clusterState: ClusterState,
        userConfig: User,
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
                    sshKeyPath = userConfigProvider.sshKeyPath,
                    clusterName = DEFAULT_CLUSTER_NAME,
                )
            val configFile = File(workingDirectory.toFile(), AXONOPS_CONFIG_FILE)
            AxonOpsWorkbenchConfig.writeToFile(config, configFile)
            outputHandler.handleMessage("AxonOps Workbench configuration written to $AXONOPS_CONFIG_FILE")
        }
}
