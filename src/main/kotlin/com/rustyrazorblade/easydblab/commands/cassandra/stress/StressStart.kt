package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.StressJobService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * Start a cassandra-easy-stress job on Kubernetes.
 *
 * This command creates a K8s Job that runs the cassandra-easy-stress container
 * to stress test a Cassandra cluster. The job runs on stress nodes and connects
 * to Cassandra nodes in the cluster.
 *
 * All arguments after the options are passed directly to cassandra-easy-stress.
 * Example: easy-db-lab stress start KeyValue -d 1h --threads 100
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "start",
    aliases = ["run"],
    description = ["Start a cassandra-easy-stress job on K8s. Args are passed to cassandra-easy-stress."],
)
class StressStart : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val stressJobService: StressJobService by inject()

    @Option(
        names = ["--name", "-n"],
        description = ["Job name (auto-generated timestamp if not provided)"],
    )
    var jobName: String? = null

    @Option(
        names = ["--image"],
        description = ["Container image (default: ${Constants.Stress.IMAGE})"],
    )
    var image: String = Constants.Stress.IMAGE

    @Parameters(
        description = ["Arguments passed directly to cassandra-easy-stress (e.g., KeyValue -d 1h --threads 100)"],
        arity = "0..*",
    )
    var stressArgs: List<String> = emptyList()

    override fun execute() {
        // Get control node from cluster state
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        // Get Cassandra nodes for contact points
        val cassandraHosts = clusterState.hosts[ServerType.Cassandra]
        if (cassandraHosts.isNullOrEmpty()) {
            error("No Cassandra nodes found. Please ensure the environment is running.")
        }

        // Build contact points string from Cassandra private IPs
        val contactPoints = cassandraHosts.joinToString(",") { it.privateIp }
        log.info { "Cassandra contact points: $contactPoints" }

        // Generate job name
        val timestamp = System.currentTimeMillis() / Constants.Time.MILLIS_PER_SECOND
        val fullJobName =
            if (jobName != null) {
                "${Constants.Stress.JOB_PREFIX}-$jobName-$timestamp"
            } else {
                "${Constants.Stress.JOB_PREFIX}-$timestamp"
            }
        log.info { "Job name: $fullJobName" }

        // Build command arguments
        val args = buildStressArgs(contactPoints)
        log.info { "Stress args: $args" }

        // Start the job via service
        stressJobService
            .startJob(
                controlHost = controlNode,
                jobName = fullJobName,
                image = image,
                args = args,
                contactPoints = contactPoints,
            ).getOrElse { e ->
                error("Failed to create job: ${e.message}")
            }

        outputHandler.handleMessage("")
        outputHandler.handleMessage("Stress job started successfully!")
        outputHandler.handleMessage("")
        outputHandler.handleMessage("Job name: $fullJobName")
        outputHandler.handleMessage("Image: $image")
        outputHandler.handleMessage("Contact points: $contactPoints")
        outputHandler.handleMessage("Stress args: ${args.joinToString(" ")}")
        outputHandler.handleMessage("")
        outputHandler.handleMessage("Check status: easy-db-lab cassandra stress status")
        outputHandler.handleMessage("View logs: easy-db-lab cassandra stress logs $fullJobName")
        outputHandler.handleMessage("Stop job: easy-db-lab cassandra stress stop $fullJobName")
    }

    /**
     * Known stress subcommands that don't require "run" prefix.
     */
    private fun isStressSubcommand(arg: String): Boolean = arg in listOf("run", "list", "info", "fields")

    /**
     * Builds the command arguments for cassandra-easy-stress.
     * Uses passthrough args from user, adding defaults for host if needed.
     */
    private fun buildStressArgs(contactPoints: String): List<String> {
        val args = mutableListOf<String>()

        // If user provided args, use them directly
        if (stressArgs.isNotEmpty()) {
            // Check if this looks like a "run" command (starts with workload name or has run keyword)
            val firstArg = stressArgs.first()
            if (!isStressSubcommand(firstArg)) {
                // Assume it's a workload name, prepend "run"
                args.add("run")
            }
            args.addAll(stressArgs)
        } else {
            error("Stress arguments are required (e.g., KeyValue -d 1h --threads 100)")
        }

        // Add host if not already specified and this is a run command
        if (args.firstOrNull() == "run") {
            if (!args.contains("--host") && !args.contains("-h")) {
                args.add("--host")
                args.add(contactPoints)
            }
        }

        return args
    }
}
