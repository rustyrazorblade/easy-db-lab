package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import com.rustyrazorblade.easydblab.services.LocalhostObservabilityHttp
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
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

    /** Removes the project's containers and network; the files in [directory] stay. */
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
 * @property http a project's backends for a tenant, over the published port of each backend port.
 * @property readyTimeout how long a service may take to answer `/ready`.
 * @property pollInterval the gap between two `/ready` looks.
 * @property beforeStart runs on a project's directory once its files are written; where a test puts
 *   its `compose.override.yml`. Production does nothing.
 */
class StackRuntime(
    val compose: ComposeCli,
    val http: (String, Map<Int, Int>) -> ObservabilityHttp = { tenant, ports -> LocalhostObservabilityHttp(tenant, ports) },
    val readyTimeout: Duration = Duration.ofMinutes(Constants.Export.Query.READY_TIMEOUT_MINUTES),
    val pollInterval: Duration = Duration.ofSeconds(2),
    val beforeStart: (File) -> Unit = {},
)

/**
 * [ExportStack] over `docker compose -p <project>`. [start] writes the project's files, starts it
 * detached, finds the loopback port compose published for each backend port, and waits until each
 * service answers `/ready` on its first port. A project that does not start, or a backend that does
 * not become ready, is removed again before the failure is reported.
 *
 * @property directory the project's directory.
 * @property project the project's name.
 * @property compose runs docker compose.
 * @property ports the published loopback port of each backend port.
 * @property http the backends for a tenant over [ports].
 */
class DockerExportStack private constructor(
    override val directory: File,
    private val project: String,
    private val compose: ComposeCli,
    private val ports: Map<Int, Int>,
    private val http: (String, Map<Int, Int>) -> ObservabilityHttp,
) : ExportStack {
    companion object {
        private val COMMAND_TIMEOUT: Duration = Duration.ofMinutes(Constants.Export.Query.COMPOSE_COMMAND_TIMEOUT_MINUTES)
        private const val STOP_SECONDS = 120
        private const val DOWN_SECONDS = 60

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
            val published = definition.published
            val compose = runtime.compose
            val files = definition.files
            val http = runtime.http
            files.forEach { (path, bytes) -> File(directory, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
            runtime.beforeStart(directory)
            val stack =
                lazy {
                    val ports =
                        published
                            .flatMap { (service, servicePorts) ->
                                servicePorts.map {
                                    it to
                                        port(compose, directory, project, service, it)
                                }
                            }.toMap()
                    DockerExportStack(directory, project, compose, ports, http)
                }
            try {
                val up = compose.run(directory, project, listOf("up", "-d"), COMMAND_TIMEOUT)
                check(up.exitCode == 0) { "docker compose could not start $project (exit ${up.exitCode}):\n${up.output}" }
                val started = stack.value
                published.forEach { (service, servicePorts) ->
                    started.awaitReady(service, servicePorts.first(), runtime.readyTimeout, runtime.pollInterval)
                }
                return started
            } catch (e: Exception) {
                down(compose, directory, project)
                throw e
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

        private fun down(
            compose: ComposeCli,
            directory: File,
            project: String,
        ) {
            compose.run(directory, project, listOf("down", "--remove-orphans", "-t", DOWN_SECONDS.toString()), COMMAND_TIMEOUT)
        }
    }

    override fun http(tenant: String): ObservabilityHttp = http(tenant, ports)

    override fun stop(service: String) {
        val result = compose.run(directory, project, listOf("stop", "-t", STOP_SECONDS.toString(), service), COMMAND_TIMEOUT)
        check(result.exitCode == 0) { "docker compose could not stop $service of $project (exit ${result.exitCode}):\n${result.output}" }
    }

    override fun close() = down(compose, directory, project)

    private fun awaitReady(
        service: String,
        port: Int,
        timeout: Duration,
        interval: Duration,
    ) {
        val deadline = Instant.now().plus(timeout)
        val attempts = (timeout.toMillis() / interval.toMillis().coerceAtLeast(1)).toInt().coerceAtLeast(1) + 1
        val ready =
            pollUntil("$project $service ready", attempts, interval, deadline, done = { it }) {
                runCatching {
                    http(
                        Constants.Observability.DEFAULT_TENANT,
                    ).get(port, "/ready").code == Constants.HttpStatus.OK
                }.getOrDefault(false)
            }
        check(ready) { "$service of $project did not answer /ready within $timeout" }
    }
}
