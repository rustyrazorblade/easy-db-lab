package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import com.rustyrazorblade.easydblab.services.LocalhostObservabilityHttp
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration
import java.time.Instant

/** A running temporary compose project of a query export: the reader or the writer. */
interface ExportStack : AutoCloseable {
    /** The project's directory. */
    val directory: File

    /** Its backends, reached on their published loopback ports, every request carrying [tenant] (`a|b` for several). */
    fun http(tenant: String): ObservabilityHttp

    /** Stops [service] gracefully, so it can finish writing what it holds. */
    fun stop(service: String)

    /** Saves the project's logs in [directory] and removes its containers and network; the files in [directory] stay. */
    override fun close()
}

/**
 * What one temporary compose project is.
 *
 * @property project its `docker compose -p` name.
 * @property files every file of it, by path relative to its directory.
 * @property published the container ports each service publishes on a random loopback port; `/ready`
 *   is checked on the first.
 */
class StackDefinition(
    val project: String,
    val files: Map<String, ByteArray>,
    val published: Map<String, List<Int>>,
)

/**
 * How temporary compose projects are run and reached.
 *
 * @property compose runs docker compose.
 * @property eventBus reports a project that cannot be removed.
 * @property client makes the one HTTP client each project's requests share; direct, with no proxy.
 * @property http a project's backends for a tenant, over the published port of each backend port and the project's client.
 * @property readyTimeout how long a service may take to answer `/ready`.
 * @property pollInterval the gap between two `/ready` looks.
 * @property beforeStart runs on a project's directory once its files are written; where a test puts
 *   its `compose.override.yml`. Production does nothing.
 */
class StackRuntime(
    val compose: ComposeCli,
    val eventBus: EventBus,
    val client: () -> OkHttpClient = LocalhostObservabilityHttp::directClient,
    val http: (String, Map<Int, Int>, OkHttpClient) -> ObservabilityHttp = { tenant, ports, client ->
        LocalhostObservabilityHttp(tenant, ports, client)
    },
    val readyTimeout: Duration = Duration.ofMinutes(Constants.Export.Query.READY_TIMEOUT_MINUTES),
    val pollInterval: Duration = Duration.ofSeconds(Constants.Export.Query.READY_POLL_SECONDS),
    val beforeStart: (File) -> Unit = {},
)

/**
 * Removes a temporary compose project, and reports it when compose cannot.
 *
 * Before it removes the project it saves `docker compose logs` in the project's directory
 * ([Constants.Export.Query.STACK_LOG_FILE]), which the kept work directory holds, so a failed export
 * leaves the operator the containers' own account. A `down` that exits non-zero is reported as
 * [Event.Export.StackNotRemoved], with the command that removes the project by hand.
 *
 * @property compose runs docker compose.
 * @property eventBus reports a project that cannot be removed.
 */
internal class ProjectRemoval(
    private val compose: ComposeCli,
    private val eventBus: EventBus,
) {
    companion object {
        private val COMMAND_TIMEOUT: Duration = Duration.ofMinutes(Constants.Export.Query.COMPOSE_COMMAND_TIMEOUT_MINUTES)
        private const val DOWN_SECONDS = Constants.Export.Query.STACK_DOWN_SECONDS
    }

    /** Where [directory]'s project logs are saved. */
    fun logFile(directory: File): File = File(directory, Constants.Export.Query.STACK_LOG_FILE)

    /** Saves the logs of [project] in [directory]; returns the file. */
    fun saveLogs(
        directory: File,
        project: String,
    ): File {
        val logs = compose.run(directory, project, listOf("logs", "--no-color", "--timestamps"), COMMAND_TIMEOUT)
        return logFile(directory).apply {
            writeText(if (logs.exitCode == 0) logs.output else "docker compose logs exited ${logs.exitCode}:\n${logs.output}")
        }
    }

    /**
     * Removes [project]; when compose cannot, reports it and returns the failure, else null. Never
     * throws for a failed `down`, so the caller's own outcome is not hidden.
     */
    fun down(
        directory: File,
        project: String,
    ): IllegalStateException? {
        val down = compose.run(directory, project, listOf("down", "--remove-orphans", "-t", DOWN_SECONDS.toString()), COMMAND_TIMEOUT)
        if (down.exitCode == 0) return null
        eventBus.emit(Event.Export.StackNotRemoved(project, directory.path, down.exitCode, down.output))
        return IllegalStateException("docker compose could not remove $project (exit ${down.exitCode}):\n${down.output}")
    }
}

/**
 * [ExportStack] over `docker compose -p <project>`. [start] writes the project's files, starts it
 * detached, finds the loopback port compose published for each backend port, and waits until each
 * service answers `/ready` on its first port. A project that does not start, or a backend that does
 * not become ready, has its logs saved and is removed again before the failure is reported, which
 * names the log file. Every request goes through one HTTP client, whose connections are released
 * when the stack closes.
 *
 * @property directory the project's directory.
 * @property project the project's name.
 * @property compose runs docker compose.
 * @property removal saves the logs and removes the project.
 * @property ports the published loopback port of each backend port.
 * @property client the one HTTP client of every request to this project.
 * @property http the backends for a tenant over [ports] and [client].
 */
class DockerExportStack private constructor(
    override val directory: File,
    private val project: String,
    private val compose: ComposeCli,
    private val removal: ProjectRemoval,
    private val ports: Map<Int, Int>,
    private val client: OkHttpClient,
    private val http: (String, Map<Int, Int>, OkHttpClient) -> ObservabilityHttp,
) : ExportStack {
    companion object {
        private val COMMAND_TIMEOUT: Duration = Duration.ofMinutes(Constants.Export.Query.COMPOSE_COMMAND_TIMEOUT_MINUTES)
        private const val STOP_SECONDS = Constants.Export.Query.STACK_STOP_SECONDS
        private const val ANSWER_EXCERPT_CHARS = 500

        /**
         * Writes [definition]'s files into [directory], runs the runtime's `beforeStart` on it (where a
         * test puts its `compose.override.yml`), starts the project, and waits until each published
         * service answers `/ready` on the first of its ports.
         */
        fun start(
            directory: File,
            definition: StackDefinition,
            runtime: StackRuntime,
        ): ExportStack {
            val project = definition.project
            val compose = runtime.compose
            val removal = ProjectRemoval(compose, runtime.eventBus)
            definition.files.forEach { (path, bytes) -> File(directory, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
            runtime.beforeStart(directory)
            val client = runtime.client()
            try {
                val up = compose.run(directory, project, listOf("up", "-d"), COMMAND_TIMEOUT)
                check(up.exitCode == 0) { "docker compose could not start $project (exit ${up.exitCode}):\n${up.output}" }
                val ports =
                    definition.published
                        .flatMap { (service, servicePorts) -> servicePorts.map { it to port(compose, directory, project, service, it) } }
                        .toMap()
                val started = DockerExportStack(directory, project, compose, removal, ports, client, runtime.http)
                definition.published.forEach { (service, servicePorts) ->
                    started.awaitReady(service, servicePorts.first(), runtime.readyTimeout, runtime.pollInterval)
                }
                return started
            } catch (e: Exception) {
                val logs = removal.saveLogs(directory, project)
                val failed = IllegalStateException("${e.message}\nThe project's docker compose logs are in ${logs.path}", e)
                removal.down(directory, project)?.let(failed::addSuppressed)
                release(client)
                throw failed
            }
        }

        private fun port(
            compose: ComposeCli,
            directory: File,
            project: String,
            service: String,
            port: Int,
        ): Int {
            val result = compose.run(directory, project, listOf("port", service, port.toString()), COMMAND_TIMEOUT)
            val published =
                result.output
                    .lines()
                    .lastOrNull { it.isNotBlank() }
                    ?.substringAfterLast(':')
                    ?.trim()
                    ?.toIntOrNull()
            check(result.exitCode == 0 && published != null) { "$project publishes no port for $service:$port: ${result.output}" }
            return published
        }

        /** Closes [client]'s idle connections and its dispatcher's threads. */
        private fun release(client: OkHttpClient) {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    override fun http(tenant: String): ObservabilityHttp = http(tenant, ports, client)

    override fun stop(service: String) {
        val result = compose.run(directory, project, listOf("stop", "-t", STOP_SECONDS.toString(), service), COMMAND_TIMEOUT)
        check(result.exitCode == 0) { "docker compose could not stop $service of $project (exit ${result.exitCode}):\n${result.output}" }
    }

    override fun close() {
        try {
            removal.saveLogs(directory, project)
            removal.down(directory, project)
        } finally {
            release(client)
        }
    }

    private fun awaitReady(
        service: String,
        port: Int,
        timeout: Duration,
        interval: Duration,
    ) {
        val deadline = Instant.now().plus(timeout)
        val attempts = (timeout.toMillis() / interval.toMillis().coerceAtLeast(1)).toInt().coerceAtLeast(1) + 1
        var last = "no answer"
        val ready =
            pollUntil("$project $service ready", attempts, interval, deadline, done = { it }) {
                runCatching { http(Constants.Observability.DEFAULT_TENANT).get(port, "/ready") }.fold(
                    onSuccess = { answer ->
                        if (answer.code !=
                            Constants.HttpStatus.OK
                        ) {
                            last = "status ${answer.code}: ${answer.body.take(ANSWER_EXCERPT_CHARS)}"
                        }
                        answer.code == Constants.HttpStatus.OK
                    },
                    onFailure = { error ->
                        last = "${error::class.simpleName}: ${error.message}"
                        false
                    },
                )
            }
        check(ready) { "$service of $project did not answer /ready within $timeout; its last answer: $last" }
    }
}
