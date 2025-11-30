package com.rustyrazorblade.easycasslab.commands

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.annotations.McpCommand
import com.rustyrazorblade.easycasslab.annotations.RequireDocker
import com.rustyrazorblade.easycasslab.annotations.RequireProfileSetup
import com.rustyrazorblade.easycasslab.commands.mixins.HostsMixin
import com.rustyrazorblade.easycasslab.configuration.AxonOpsWorkbenchConfig
import com.rustyrazorblade.easycasslab.configuration.ClusterStateManager
import com.rustyrazorblade.easycasslab.configuration.ServerType
import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.containers.Terraform
import com.rustyrazorblade.easycasslab.providers.aws.RetryUtil
import com.rustyrazorblade.easycasslab.services.K3sAgentService
import com.rustyrazorblade.easycasslab.services.K3sService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Starts instances and sets up the cluster.
 */
@McpCommand
@RequireDocker
@RequireProfileSetup
@Command(
    name = "up",
    description = ["Starts instances"],
)
class Up(
    context: Context,
) : PicoBaseCommand(context) {
    private val userConfig: User by inject()
    private val k3sService: K3sService by inject()
    private val k3sAgentService: K3sAgentService by inject()
    private val clusterStateManager: ClusterStateManager by inject()

    companion object {
        private val log = KotlinLogging.logger {}
        private val SSH_STARTUP_DELAY = Duration.ofSeconds(5)
    }

    @Option(names = ["--no-setup", "-n"])
    var noSetup = false

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        provisionInfrastructure()
        writeConfigurationFiles()
        updateClusterState()
        WriteConfig(context).execute()
        waitForSshAndDownloadVersions()
        setupInstancesIfNeeded()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun updateClusterState() {
        try {
            val clusterState = clusterStateManager.load()
            val allHosts = tfstate.getAllHostsAsMap()
            clusterState.updateHosts(allHosts)
            clusterState.markInfrastructureUp()
            // Store VPC ID for use by other commands (e.g., down)
            clusterState.vpcId = tfstate.getVpcId()
            clusterStateManager.save(clusterState)
            outputHandler.handleMessage("Cluster state updated: ${allHosts.values.flatten().size} hosts tracked")
        } catch (e: Exception) {
            log.warn(e) { "Failed to update cluster state, continuing anyway" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun provisionInfrastructure() {
        val terraform = Terraform(context)
        with(TermColors()) {
            terraform
                .up()
                .onFailure {
                    log.error(it) { "Terraform provisioning failed" }
                    outputHandler.handleError(it.message ?: "Unknown error")
                    outputHandler.handleMessage(
                        red("Some resources may have been unsuccessfully provisioned.") +
                            "  Rerun " + green("easy-cass-lab up") + " to provision the remaining resources.",
                    )
                    exitProcess(1)
                }.onSuccess {
                    outputHandler.handleMessage(
                        "Instances have been provisioned.\n\n" +
                            "Use " + green("easy-cass-lab list") + " to see all available versions\n\n" +
                            "Then use " + green("easy-cass-lab use <version>") +
                            " to use a specific version of Cassandra.\n",
                    )
                    outputHandler.handleMessage("Writing ssh config file to sshConfig.")
                    outputHandler.handleMessage(
                        "The following alias will allow you to easily work with the cluster:\n\n" +
                            green("source env.sh") + "\n",
                    )
                    outputHandler.handleMessage(
                        "You can edit " + green("cassandra.patch.yaml") +
                            " with any changes you'd like to see merge in into the remote cassandra.yaml file.",
                    )
                }
        }
    }

    private fun writeConfigurationFiles() {
        val config = File("sshConfig").bufferedWriter()
        tfstate.writeSshConfig(config, userConfig.sshKeyPath)
        val envFile = File("env.sh").bufferedWriter()
        tfstate.writeEnvironmentFile(envFile)
        writeStressEnvironmentVariables()
        writeAxonOpsWorkbenchConfig()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeAxonOpsWorkbenchConfig() {
        try {
            val cassandraHosts = tfstate.getHosts(ServerType.Cassandra)
            if (cassandraHosts.isNotEmpty()) {
                val cassandra0 = cassandraHosts.first()
                val config =
                    AxonOpsWorkbenchConfig.create(
                        host = cassandra0,
                        userConfig = userConfig,
                        clusterName = "easy-cass-lab",
                    )
                val configFile = File("axonops-workbench.json")
                AxonOpsWorkbenchConfig.writeToFile(config, configFile)
                outputHandler.handleMessage(
                    "AxonOps Workbench configuration written to axonops-workbench.json",
                )
            } else {
                log.warn { "No Cassandra hosts found, skipping AxonOps Workbench configuration" }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to write AxonOps Workbench configuration" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeStressEnvironmentVariables() {
        val cassandraHost = tfstate.getHosts(ServerType.Cassandra).first().private

        val clusterState =
            try {
                clusterStateManager.load()
            } catch (e: Exception) {
                null
            }

        val datacenter = clusterState?.initConfig?.region ?: userConfig.region

        val stressEnvironmentVars = File("environment.sh").bufferedWriter()
        stressEnvironmentVars.write("#!/usr/bin/env bash")
        stressEnvironmentVars.newLine()
        stressEnvironmentVars.write("export CASSANDRA_EASY_STRESS_CASSANDRA_HOST=$cassandraHost")
        stressEnvironmentVars.newLine()
        stressEnvironmentVars.write("export CASSANDRA_EASY_STRESS_PROM_PORT=0")
        stressEnvironmentVars.newLine()
        stressEnvironmentVars.write("export CASSANDRA_EASY_STRESS_DEFAULT_DC=$datacenter")
        stressEnvironmentVars.newLine()
        stressEnvironmentVars.flush()
        stressEnvironmentVars.close()
    }

    private fun waitForSshAndDownloadVersions() {
        outputHandler.handleMessage("Waiting for SSH to come up..")
        Thread.sleep(SSH_STARTUP_DELAY.toMillis())

        val retryConfig = RetryUtil.createSshConnectionRetryConfig()
        val retry =
            Retry.of("ssh-connection", retryConfig).also {
                it.eventPublisher.onRetry { event ->
                    outputHandler.handleMessage(
                        "SSH still not up yet, waiting... (attempt ${event.numberOfRetryAttempts})",
                    )
                }
            }

        Retry
            .decorateRunnable(retry) {
                tfstate.withHosts(ServerType.Cassandra, hosts) {
                    remoteOps.executeRemotely(it, "echo 1").text
                    if (!File("cassandra_versions.yaml").exists()) {
                        remoteOps.download(
                            it,
                            "/etc/cassandra_versions.yaml",
                            Path.of("cassandra_versions.yaml"),
                        )
                    }
                }
            }.run()
    }

    private fun setupInstancesIfNeeded() {
        if (noSetup) {
            with(TermColors()) {
                outputHandler.handleMessage(
                    "Skipping node setup.  You will need to run " +
                        green("easy-cass-lab setup-instance") + " to complete setup",
                )
            }
        } else {
            SetupInstance(context).execute()
            startK3sOnAllNodes()

            if (userConfig.axonOpsKey.isNotBlank() && userConfig.axonOpsOrg.isNotBlank()) {
                outputHandler.handleMessage("Setting up axonops for ${userConfig.axonOpsOrg}")
                ConfigureAxonOps(context).execute()
            }
        }
    }

    @Suppress("ReturnCount")
    private fun startK3sOnAllNodes() {
        outputHandler.handleMessage("Starting K3s cluster...")

        val controlHosts = tfstate.getHosts(ServerType.Control)
        if (controlHosts.isEmpty()) {
            outputHandler.handleError("No control nodes found, skipping K3s setup")
            return
        }

        val controlNode = controlHosts.first()
        outputHandler.handleMessage("Starting K3s server on control node ${controlNode.alias}...")

        k3sService
            .start(controlNode)
            .onFailure { error ->
                log.error(error) { "Failed to start K3s server on ${controlNode.alias}" }
                outputHandler.handleError("Failed to start K3s server: ${error.message}")
                return
            }.onSuccess {
                log.info { "Successfully started K3s server on ${controlNode.alias}" }
            }

        val nodeToken =
            k3sService
                .getNodeToken(controlNode)
                .onFailure { error ->
                    log.error(error) { "Failed to retrieve K3s node token from ${controlNode.alias}" }
                    outputHandler.handleError("Failed to retrieve K3s node token: ${error.message}")
                    return
                }.getOrThrow()

        log.info { "Retrieved K3s node token from ${controlNode.alias}" }

        k3sService
            .downloadAndConfigureKubeconfig(controlNode, File("kubeconfig").toPath())
            .onFailure { error ->
                log.error(error) { "Failed to download kubeconfig from ${controlNode.alias}" }
                outputHandler.handleError("Failed to download kubeconfig: ${error.message}")
            }.onSuccess {
                outputHandler.handleMessage("Kubeconfig written to kubeconfig")
                outputHandler.handleMessage("Use 'source env.sh' to configure kubectl for cluster access")
            }

        val serverUrl = "https://${controlNode.private}:6443"
        val workerServerTypes = listOf(ServerType.Cassandra, ServerType.Stress)

        workerServerTypes.forEach { serverType ->
            val nodeLabels =
                when (serverType) {
                    ServerType.Cassandra -> mapOf("role" to "cassandra", "type" to "db")
                    ServerType.Stress -> mapOf("role" to "stress", "type" to "app")
                    ServerType.Control -> emptyMap()
                }

            tfstate.withHosts(serverType, hosts, parallel = true) { host ->
                outputHandler.handleMessage("Configuring K3s agent on ${host.alias} with labels: $nodeLabels...")

                k3sAgentService
                    .configure(host, serverUrl, nodeToken, nodeLabels)
                    .onFailure { error ->
                        log.error(error) { "Failed to configure K3s agent on ${host.alias}" }
                        outputHandler.handleError("Failed to configure K3s agent on ${host.alias}: ${error.message}")
                        return@withHosts
                    }

                k3sAgentService
                    .start(host)
                    .onFailure { error ->
                        log.error(error) { "Failed to start K3s agent on ${host.alias}" }
                        outputHandler.handleError("Failed to start K3s agent on ${host.alias}: ${error.message}")
                    }.onSuccess {
                        log.info { "Successfully started K3s agent on ${host.alias}" }
                    }
            }
        }

        outputHandler.handleMessage("K3s cluster started successfully")
    }
}
