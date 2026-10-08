package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.profiling.ProfilingConfig
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import com.rustyrazorblade.easydblab.services.CassandraProfilingService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import java.nio.file.Path
import java.time.Instant

/**
 * Runs setup_instance.sh on all Cassandra instances.
 */
@RequireProfileSetup
@Command(
    name = "setup-instances",
    aliases = ["si"],
    description = ["Runs setup_instance.sh on all Cassandra instances"],
)
class SetupInstance : PicoBaseCommand() {
    private companion object {
        /** The prefix `setup_instance.sh` puts on the line that says why it failed. */
        const val SCRIPT_ERROR_PREFIX = "ERROR: "

        /** The script's own `ERROR:` lines, or the remote failure itself when it printed none. */
        fun setupFailureReason(e: RemoteCommandFailedException): String =
            (e.stderr + "\n" + e.stdout)
                .lines()
                .filter { it.startsWith(SCRIPT_ERROR_PREFIX) }
                .joinToString("; ") { it.removePrefix(SCRIPT_ERROR_PREFIX).trim() }
                .ifEmpty { e.message ?: "setup_instance.sh exited non-zero" }
    }

    private val hostOperationsService: HostOperationsService by inject()
    private val profilingService: CassandraProfilingService by inject()

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        fun setup(host: Host) {
            remoteOps.upload(host, Path.of("environment.sh"), "environment.sh")
            remoteOps.executeRemotely(host, "sudo mv environment.sh /etc/profile.d/stress.sh").text
        }

        // The OTel Java agent reads these JMX rules at Cassandra startup. They live in the cluster
        // workspace rather than in the AMI, so an operator can change a rule, re-run this command
        // and restart Cassandra - no rebake, no Gradle build.
        fun writeJmxRules(host: Host) {
            remoteOps.executeRemotely(host, "sudo mkdir -p ${Constants.Cassandra.NODE_CONFIG_DIR}").text
            remoteOps.upload(host, Path.of(Constants.Cassandra.JMX_RULES_FILE), Constants.Cassandra.JMX_RULES_FILE)
            remoteOps
                .executeRemotely(
                    host,
                    "sudo mv ${Constants.Cassandra.JMX_RULES_FILE} ${Constants.Cassandra.JMX_RULES_PATH}",
                ).text
        }

        fun setupStressSystemdEnv(
            host: Host,
            cassandraHost: String,
            datacenter: String,
        ) {
            // Create systemd environment file locally
            val envFile = java.io.File.createTempFile("stress", ".env")
            try {
                envFile.bufferedWriter().use { writer ->
                    writer.write("CASSANDRA_EASY_STRESS_CASSANDRA_HOST=$cassandraHost")
                    writer.newLine()
                    writer.write("CASSANDRA_EASY_STRESS_PROM_PORT=0")
                    writer.newLine()
                    writer.write("CASSANDRA_EASY_STRESS_DEFAULT_DC=$datacenter")
                    writer.newLine()
                }

                // Create directory and upload the file
                remoteOps.executeRemotely(host, "sudo mkdir -p /etc/cassandra-easy-stress").text
                remoteOps.upload(host, envFile.toPath(), "stress.env")
                remoteOps.executeRemotely(host, "sudo mv stress.env /etc/cassandra-easy-stress/stress.env").text
            } finally {
                envFile.delete()
            }
        }

        // Cluster-up is the only moment that knows both the Pyroscope address and the cluster name,
        // so it seeds desired profiling state here. That is what makes a freshly provisioned cluster
        // profile CPU with no operator action: the node's reconciler picks this up on its next pass.
        fun seedProfilingConfig(
            host: Host,
            controlNodeIp: String,
            clusterName: String,
        ) {
            profilingService.writeDesiredState(
                host,
                ProfilingConfig(
                    enabled = true,
                    asprofArgs = Constants.Profiling.DEFAULT_ASPROF_ARGS,
                    loopInterval = Constants.Profiling.DEFAULT_LOOP_INTERVAL,
                    retentionMinutes = Constants.Profiling.DEFAULT_RETENTION_MINUTES,
                    maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES,
                    pyroscopeUrl = pyroscopeIngestBaseUrl(controlNodeIp, clusterState.initConfig?.telemetryRedirect),
                    clusterName = clusterName,
                    tenant = clusterState.tenant(),
                    updatedAt = Instant.now().toString(),
                ),
            )
        }

        // A non-zero setup (no data disk, a failed mount) fails the command, naming the host and the
        // reason the script printed, so `up` stops before K3s starts.
        fun runSetupScript(host: Host) {
            remoteOps.upload(host, Path.of("setup_instance.sh"), "setup_instance.sh")
            try {
                remoteOps.executeRemotely(host, "sudo bash setup_instance.sh").text
            } catch (e: RemoteCommandFailedException) {
                eventBus.emit(Event.Provision.InstanceSetupFailed(host = host.alias, reason = setupFailureReason(e)))
                throw CommandFailedException("Instance setup failed on ${host.alias}")
            }
        }

        // Get datacenter once from the first stress instance (all instances are in the same DC)
        val stressHosts = clusterState.getHosts(ServerType.Stress)
        val datacenter =
            if (stressHosts.isNotEmpty()) {
                val datacenterResponse =
                    remoteOps.executeRemotely(
                        stressHosts.first(),
                        "curl -s http://169.254.169.254/latest/dynamic/instance-identity/document | yq .region",
                    )
                datacenterResponse.text.trim()
            } else {
                ""
            }

        val cassandraHost = clusterState.getHosts(ServerType.Cassandra).first().private
        val controlNodeIp = clusterState.getHosts(ServerType.Control).first().private
        val clusterName = clusterState.clusterLabelName()

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Stress, hosts.hostList, parallel = true) { host ->
            val h = host.toHost()
            setup(h)
            setupStressSystemdEnv(h, cassandraHost, datacenter)
            remoteOps.executeRemotely(h, "sudo hostnamectl set-hostname ${h.alias}").text
            runSetupScript(h)
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, "") { host ->
            val h = host.toHost()
            setup(h)
            writeJmxRules(h)
            seedProfilingConfig(h, controlNodeIp, clusterName)
            remoteOps.executeRemotely(h, "sudo hostnamectl set-hostname ${h.alias}").text
            runSetupScript(h)
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Control, "") { host ->
            val h = host.toHost()
            setup(h)
            remoteOps.executeRemotely(h, "sudo hostnamectl set-hostname ${h.alias}").text
            runSetupScript(h)
        }
    }
}
