package com.rustyrazorblade.easydblab.localstack

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The `compose.override.yml` a test puts beside a generated stack, as an operator would: compose merges
 * it into `compose.yml` on its own. It joins the stack to the test's network, points the signer at
 * MinIO, publishes ports for the test, and adds services. Written with kaml, as the stack is.
 *
 * @property networks the project's networks; the default one is the test's, external to the project.
 * @property services the services added or changed.
 */
@Serializable
data class ComposeOverride(
    val networks: Map<String, ExternalNetwork>,
    val services: Map<String, ServiceOverride>,
) {
    /** This file as YAML. */
    fun toYaml(): String = YAML.encodeToString(serializer(), this)

    private companion object {
        val YAML = Yaml(configuration = YamlConfiguration(encodeDefaults = false))
    }
}

/**
 * A network made outside the project.
 *
 * @property name the Docker network's name.
 * @property external always true: compose neither creates nor removes it.
 */
@Serializable
data class ExternalNetwork(
    val name: String,
    val external: Boolean,
)

/**
 * The service a [ServiceOverride] starts from.
 *
 * @property file the compose file that defines it.
 * @property service its name there.
 */
@Serializable
data class ComposeExtends(
    val file: String,
    val service: String,
)

/** What an override sets on one service; unset fields keep the base file's. Mounts merge by target. */
@Serializable
data class ServiceOverride(
    val extends: ComposeExtends? = null,
    val command: List<String>? = null,
    val environment: Map<String, String>? = null,
    val volumes: List<ComposeMount>? = null,
    val ports: List<String>? = null,
    val healthcheck: ComposeHealthcheck? = null,
    @SerialName("depends_on")
    val dependsOn: Map<String, ComposeDependency>? = null,
    val restart: String? = null,
)

/**
 * A docker compose project in [directory], run with the `docker compose` CLI, as the operator runs it.
 * Compose reads `compose.yml` and `compose.override.yml` from the directory on its own.
 *
 * @property directory the stack directory.
 * @property name the project name, unique per test run.
 */
class ComposeProject(
    private val directory: File,
    val name: String,
) {
    private companion object {
        const val COMMAND_TIMEOUT_MINUTES = 20L
    }

    /** What one `docker compose` command did. */
    data class Result(
        val exitCode: Int,
        val output: String,
    )

    /** Runs `docker compose -p <name> <args>` in the stack directory. */
    fun compose(vararg args: String): Result = run(listOf("docker", "compose", "-p", name) + args)

    /** Runs `docker compose` with [args] and fails with its output unless it exits 0. */
    fun composeOrFail(vararg args: String): String {
        val result = compose(*args)
        check(result.exitCode == 0) { "docker compose ${args.joinToString(" ")} exited ${result.exitCode}:\n${result.output}" }
        return result.output
    }

    /** The host port on `127.0.0.1` that [service] publishes for its [port]. */
    fun port(
        service: String,
        port: Int,
    ): Int =
        composeOrFail("port", service, port.toString())
            .trim()
            .lines()
            .last()
            .substringAfterLast(':')
            .toInt()

    /** The container id of [service], running or exited. */
    fun containerId(service: String): String =
        composeOrFail("ps", "-a", "-q", service)
            .trim()
            .lines()
            .last()

    /** The container ids of every service, by service, running or exited. */
    fun containerIds(services: Collection<String>): Map<String, String> = services.associateWith(::containerId)

    /** [service]'s logs, with no prefix. */
    fun logs(service: String): String = composeOrFail("logs", "--no-color", "--no-log-prefix", service)

    /** A field of `docker inspect` of [service]'s container, by Go template. */
    fun inspect(
        service: String,
        template: String,
    ): String = run(listOf("docker", "inspect", "--format", template, containerId(service))).output.trim()

    private fun run(command: List<String>): Result {
        val process =
            ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { start() }
        check(process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            "${command.joinToString(" ")} did not finish in $COMMAND_TIMEOUT_MINUTES minutes:\n$output"
        }
        reader.join()
        return Result(process.exitValue(), output.toString())
    }
}
