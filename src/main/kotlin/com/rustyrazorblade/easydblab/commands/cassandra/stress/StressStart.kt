package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.StressJobConfig
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
@RequiresProxy
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
        description = ["Job name (auto-generated from workload name if not provided)"],
    )
    var jobName: String? = null

    @Option(
        names = ["--image"],
        description = ["Container image (default: ${Constants.Stress.IMAGE})"],
    )
    var image: String = Constants.Stress.IMAGE

    @Option(
        names = ["--tags"],
        description = ["Custom tags added to metrics (format: key=value,key=value)"],
    )
    var tags: String? = null

    @Parameters(
        description = ["Arguments passed directly to cassandra-easy-stress (e.g., KeyValue -d 1h --threads 100)"],
        arity = "0..*",
    )
    var stressArgs: List<String> = emptyList()

    override fun execute() {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        val cassandraHosts = clusterState.hosts[ServerType.Cassandra]
        if (cassandraHosts.isNullOrEmpty()) {
            error("No Cassandra nodes found. Please ensure the environment is running.")
        }

        val contactPoints = cassandraHosts.first().privateIp
        log.info { "Cassandra contact point: $contactPoints" }

        // Increment counter for port and auto-naming
        val counter = clusterStateManager.incrementStressJobCounter()
        val promPort = Constants.Stress.PROMETHEUS_PORT + counter

        // Generate job name
        val fullJobName =
            jobName
                ?: run {
                    val workloadName = extractWorkloadName(stressArgs)
                    "$workloadName-$counter"
                }
        log.info { "Job name: $fullJobName" }

        val args = buildStressArgs(contactPoints)
        log.info { "Stress args: $args" }

        val parsedTags = parseTags(tags)

        stressJobService
            .startJob(
                controlHost = controlNode,
                config =
                    StressJobConfig(
                        jobName = fullJobName,
                        image = image,
                        args = args,
                        contactPoints = contactPoints,
                        tags = parsedTags,
                        promPort = promPort,
                    ),
            ).getOrElse { e ->
                error("Failed to create job: ${e.message}")
            }

        eventBus.emit(
            Event.Stress.JobStarted(
                jobName = fullJobName,
                image = image,
                contactPoint = contactPoints,
                promPort = promPort,
                args = args,
            ),
        )
    }

    /**
     * Parses a comma-separated key=value string into a Map.
     */
    internal fun parseTags(tagsString: String?): Map<String, String> {
        if (tagsString.isNullOrBlank()) return emptyMap()

        return tagsString
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { entry -> parseTag(entry, tagsString) }
    }

    /**
     * Parses one `key=value` tag, refusing anything the telemetry sinks cannot carry.
     *
     * Rejecting is deliberate, and the alternative is worse. These tags are joined into a
     * `k=v,k=v` string that reaches three parsers — the sidecar's `OTEL_RESOURCE_ATTRIBUTES`, the
     * stress JVM's `-Dotel.resource.attributes`, and whatever is added next — so escaping would
     * mean three encoders that all have to agree. One gate that says no is smaller and cannot
     * disagree with itself.
     *
     * Whitespace is the one that stops a run dead rather than merely spoiling a label.
     * `JAVA_TOOL_OPTIONS` is a single string the JVM splits on whitespace, so `--tags "note=first
     * run"` used to yield `-Dotel.resource.attributes=...,note=first` followed by a bare `run`, and
     * the JVM exited with `Unrecognized option: run` before cassandra-easy-stress ever started.
     *
     * Nothing here trims away or substitutes the offending character. Silently altering what the
     * operator typed is how a run ends up labelled with something other than what they asked for,
     * and a comparison keyed on that label then gives a confidently wrong answer.
     *
     * Space AROUND a tag is fine and is trimmed: `--tags "a=1, b=2"` is a normal way to type it.
     */
    private fun parseTag(
        entry: String,
        original: String,
    ): Pair<String, String> {
        require(entry.contains("=")) {
            "Invalid --tags entry \"$entry\" in \"$original\": expected key=value. " +
                "A comma always separates tags, so a value cannot contain one."
        }

        val key = entry.substringBefore("=").trim()
        val value = entry.substringAfter("=").trim()

        require(key.isNotEmpty()) {
            "Invalid --tags entry \"$entry\": the key is empty. Expected key=value."
        }
        require(key.none { it.isWhitespace() }) {
            "Invalid --tags key \"$key\": keys cannot contain whitespace."
        }
        require(value.none { it.isWhitespace() }) {
            "Invalid --tags value for \"$key\": \"$value\" contains whitespace, which would split " +
                "the stress JVM's options and stop the job starting. Use an underscore or a dash."
        }
        require(!value.contains("=")) {
            "Invalid --tags value for \"$key\": \"$value\" contains '=', which the telemetry " +
                "sinks cannot unambiguously parse."
        }

        return key to value
    }

    /**
     * Extracts the workload name from stress args.
     * The workload name is the first arg after "run" (or the first arg if "run" is implicit).
     * Returns lowercased name suitable for K8s resource naming.
     */
    internal fun extractWorkloadName(args: List<String>): String {
        if (args.isEmpty()) return "stress"
        val firstArg = args.first()
        return if (isStressSubcommand(firstArg)) {
            args.getOrNull(1)?.lowercase() ?: "stress"
        } else {
            firstArg.lowercase()
        }
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
