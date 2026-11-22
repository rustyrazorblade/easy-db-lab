package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.nodeGroups
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.profiling.ProfilingConfig
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.installFile
import com.rustyrazorblade.easydblab.shellQuote
import java.nio.file.Files
import java.time.Instant

/**
 * `setup_instance.sh` exited non-zero on [alias], for [reason] (the script's own `ERROR:` lines).
 * The caller reports it: `setup-instances` as [Event.Provision.InstanceSetupFailed], and `up`
 * as the node's failed setup step, so it is printed once either way.
 */
class InstanceSetupFailedException(
    val alias: String,
    val reason: String,
) : RuntimeException("Instance setup failed on $alias: $reason")

/**
 * The cluster facts a node's setup needs: the database host app nodes point stress at, the
 * control node Pyroscope runs on, the names profiling data is filed under, and the instance type
 * of each server type, which node tuning names when it skips a setting the instance lacks. They
 * come from `state.json` once, so every node's setup reads the same values.
 */
data class NodeSetupContext(
    val databaseHost: String,
    val controlNodeIp: String,
    val clusterName: String,
    val tenant: String,
    val telemetryRedirect: TelemetryRedirect? = null,
    val instanceTypes: Map<ServerType, String> = emptyMap(),
) {
    /** Building a context from the cluster state. */
    companion object {
        /**
         * The context for [state]: db0 is the database host, the first control node runs Pyroscope.
         * A cluster with no database nodes has no database host; its app nodes get an empty one.
         */
        fun from(state: ClusterState): NodeSetupContext =
            NodeSetupContext(
                databaseHost =
                    state
                        .getHosts(ServerType.Cassandra)
                        .firstOrNull()
                        ?.private
                        .orEmpty(),
                controlNodeIp = state.getHosts(ServerType.Control).first().private,
                clusterName = state.clusterLabelName(),
                tenant = state.tenant(),
                telemetryRedirect = state.initConfig?.telemetryRedirect,
                instanceTypes =
                    state.initConfig
                        ?.nodeGroups()
                        ?.associate { it.serverType to it.instanceType }
                        .orEmpty(),
            )
    }
}

/**
 * One node's setup, by server type: the workspace's `environment.sh`, the per-type files, the
 * hostname, then `setup_instance.sh`. It exists apart from the `setup-instances` command so `up`
 * can run it inside each node's own pipeline, with no wait on any other node.
 *
 * An app node reads its own region from instance metadata for the stress default datacenter;
 * every node is in one region, so the value is the same one the first app node would give. The
 * workspace files it uploads are read from [context]'s workspace directory, whatever directory the
 * process started in.
 */
class NodeSetupService(
    private val remoteOps: RemoteOperationsService,
    private val profilingService: CassandraProfilingService,
    private val tuningFiles: NodeTuningFiles,
    private val context: Context,
) {
    /** Sets up [host] as a node of [serverType]. */
    fun setup(
        serverType: ServerType,
        host: Host,
        context: NodeSetupContext,
    ) = when (serverType) {
        ServerType.Cassandra -> setupDatabase(host, context)
        ServerType.Stress -> setupApp(host, context)
        ServerType.Control -> setupControl(host, context)
    }

    /** A database node: JMX rules and seeded profiling state as well as the common setup. */
    fun setupDatabase(
        host: Host,
        context: NodeSetupContext,
    ) {
        installEnvironment(host)
        writeJmxRules(host)
        seedProfilingConfig(host, context)
        common(host, context.instanceTypes[ServerType.Cassandra])
    }

    /** An app node: the stress service's environment file as well as the common setup. */
    fun setupApp(
        host: Host,
        context: NodeSetupContext,
    ) {
        installEnvironment(host)
        installStressEnv(host, context.databaseHost, readRegion(host))
        common(host, context.instanceTypes[ServerType.Stress])
    }

    /** The control node: the common setup only. */
    fun setupControl(
        host: Host,
        context: NodeSetupContext,
    ) {
        installEnvironment(host)
        common(host, context.instanceTypes[ServerType.Control])
    }

    /**
     * Installs the workspace's `environment.sh` as the login profile every shell on [host] reads.
     * The file names db0, so a cluster with no database nodes has none, and there is nothing to
     * install.
     */
    fun installEnvironment(host: Host) {
        val environment = context.workspaceFile(Constants.Workspace.STRESS_ENV_FILE).toPath()
        if (!Files.exists(environment)) return
        remoteOps.installFile(host, environment, ENVIRONMENT_PATH, FILE_MODE)
    }

    /** Writes the stress service's environment file on [host], naming [databaseHost] and [datacenter]. */
    fun installStressEnv(
        host: Host,
        databaseHost: String,
        datacenter: String,
    ) {
        val envFile = Files.createTempFile("stress", ".env")
        try {
            Files.writeString(
                envFile,
                """
                CASSANDRA_EASY_STRESS_CASSANDRA_HOST=$databaseHost
                CASSANDRA_EASY_STRESS_PROM_PORT=0
                CASSANDRA_EASY_STRESS_DEFAULT_DC=$datacenter

                """.trimIndent(),
            )
            remoteOps.executeRemotely(host, "sudo mkdir -p $STRESS_ENV_DIR").text
            remoteOps.installFile(host, envFile, "$STRESS_ENV_DIR/stress.env", FILE_MODE)
        } finally {
            Files.deleteIfExists(envFile)
        }
    }

    /** The region [host] runs in, from its instance metadata. */
    fun readRegion(host: Host): String = ImdsRegion.parse(remoteOps.executeRemotely(host, ImdsRegion.COMMAND).text)

    private fun common(
        host: Host,
        instanceType: String?,
    ) {
        remoteOps.executeRemotely(host, "sudo hostnamectl set-hostname ${host.alias.shellQuote()}").text
        runSetupScript(host, instanceType)
    }

    // The OTel Java agent reads these JMX rules at Cassandra startup. They live in the cluster
    // workspace rather than in the AMI, so an operator can change a rule, re-run setup-instances
    // and restart Cassandra - no rebake, no Gradle build.
    private fun writeJmxRules(host: Host) {
        remoteOps.executeRemotely(host, "sudo mkdir -p ${Constants.Cassandra.NODE_CONFIG_DIR}").text
        val rules = context.workspaceFile(Constants.Cassandra.JMX_RULES_FILE).toPath()
        remoteOps.installFile(host, rules, Constants.Cassandra.JMX_RULES_PATH, FILE_MODE)
    }

    // Cluster-up is the only moment that knows both the Pyroscope address and the cluster name,
    // so it seeds desired profiling state here. That is what makes a freshly provisioned cluster
    // profile CPU with no operator action: the node's reconciler picks this up on its next pass.
    private fun seedProfilingConfig(
        host: Host,
        context: NodeSetupContext,
    ) {
        profilingService.writeDesiredState(
            host,
            ProfilingConfig(
                enabled = true,
                asprofArgs = Constants.Profiling.DEFAULT_ASPROF_ARGS,
                loopInterval = Constants.Profiling.DEFAULT_LOOP_INTERVAL,
                retentionMinutes = Constants.Profiling.DEFAULT_RETENTION_MINUTES,
                maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES,
                pyroscopeUrl = pyroscopeIngestBaseUrl(context.controlNodeIp, context.telemetryRedirect),
                clusterName = context.clusterName,
                tenant = context.tenant,
                updatedAt = Instant.now().toString(),
            ),
        )
    }

    // A non-zero setup (no data disk, a failed mount, a tuning value that did not take) fails the
    // node, naming the host and the reason the script printed, so the node never joins K3s. The
    // packaged OS tuning files go beside the script, which installs them. The instance type comes
    // from the cluster state, so nothing on the node asks the cloud provider for it.
    private fun runSetupScript(
        host: Host,
        instanceType: String?,
    ) {
        tuningFiles.stage { dir ->
            remoteOps.executeRemotely(host, "rm -rf ${Constants.NodeTuning.REMOTE_DIR}", output = false)
            remoteOps.uploadDirectory(host, dir, Constants.NodeTuning.REMOTE_DIR)
        }
        remoteOps.upload(host, context.workspaceFile(SETUP_SCRIPT).toPath(), SETUP_SCRIPT)
        try {
            remoteOps.executeRemotely(host, setupCommand(instanceType)).text
        } catch (e: RemoteCommandFailedException) {
            throw InstanceSetupFailedException(host.alias, setupFailureReason(e))
        }
    }

    /** File names and the reason parse. */
    companion object {
        private const val ENVIRONMENT_PATH = "/etc/profile.d/stress.sh"
        private const val STRESS_ENV_DIR = "/etc/cassandra-easy-stress"
        private const val SETUP_SCRIPT = Constants.Workspace.SETUP_INSTANCE_SCRIPT
        private const val FILE_MODE = "0644"

        /** The prefix `setup_instance.sh` puts on the line that says why it failed. */
        private const val SCRIPT_ERROR_PREFIX = "ERROR: "

        /** How the setup script is run, as root, with the node's [instanceType] when the state has one. */
        fun setupCommand(instanceType: String?): String {
            val environment =
                instanceType?.let { "${Constants.NodeTuning.INSTANCE_TYPE_VARIABLE}=${it.shellQuote()} " }.orEmpty()
            return "sudo ${environment}bash $SETUP_SCRIPT"
        }

        /** The script's own `ERROR:` lines, or the remote failure itself when it printed none. */
        fun setupFailureReason(e: RemoteCommandFailedException): String =
            (e.stderr + "\n" + e.stdout)
                .lines()
                .filter { it.startsWith(SCRIPT_ERROR_PREFIX) }
                .joinToString("; ") { it.removePrefix(SCRIPT_ERROR_PREFIX).trim() }
                .ifEmpty { e.message ?: "$SETUP_SCRIPT exited non-zero" }
    }
}
