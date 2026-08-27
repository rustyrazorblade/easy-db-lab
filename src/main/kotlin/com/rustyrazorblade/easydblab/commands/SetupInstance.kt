package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
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
    private val hostOperationsService: HostOperationsService by inject()
    private val profilingService: CassandraProfilingService by inject()

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        fun setup(host: Host) {
            remoteOps.upload(host, Path.of("environment.sh"), "environment.sh")
            remoteOps.executeRemotely(host, "sudo mv environment.sh /etc/profile.d/stress.sh").text
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
                    pyroscopeUrl = pyroscopeIngestBaseUrl(controlNodeIp),
                    clusterName = clusterName,
                    updatedAt = Instant.now().toString(),
                ),
            )
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
            remoteOps.upload(h, Path.of("setup_instance.sh"), "setup_instance.sh")
            remoteOps.executeRemotely(h, "sudo bash setup_instance.sh").text
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, "") { host ->
            val h = host.toHost()
            setup(h)
            seedProfilingConfig(h, controlNodeIp, clusterName)
            remoteOps.executeRemotely(h, "sudo hostnamectl set-hostname ${h.alias}").text
            remoteOps.upload(h, Path.of("setup_instance.sh"), "setup_instance.sh")
            remoteOps.executeRemotely(h, "sudo bash setup_instance.sh").text
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Control, "") { host ->
            val h = host.toHost()
            setup(h)
            remoteOps.executeRemotely(h, "sudo hostnamectl set-hostname ${h.alias}").text
            remoteOps.upload(h, Path.of("setup_instance.sh"), "setup_instance.sh")
            remoteOps.executeRemotely(h, "sudo bash setup_instance.sh").text
        }
    }
}
