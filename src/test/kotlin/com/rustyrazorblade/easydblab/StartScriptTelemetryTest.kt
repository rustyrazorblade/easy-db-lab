package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Runs the Gradle-generated start script — the launcher a Homebrew user runs — against a stub
 * `java` that prints its arguments, to check which OpenTelemetry exporter settings reach the JVM.
 *
 * The agent exports to `localhost:4318` by default. With no collector there, every export fails
 * and the agent prints a stack trace on every run, so the script turns the exporters off unless the
 * user configured an OTLP endpoint or chose exporters. A user who did must get their telemetry.
 */
class StartScriptTelemetryTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var javaHome: File

    private val startScript: File by lazy {
        File(requireNotNull(System.getProperty(START_SCRIPT_PROPERTY)) { "$START_SCRIPT_PROPERTY is not set by the test task" })
    }

    @BeforeEach
    fun stubJava() {
        javaHome = File(tempDir, "jdk")
        File(javaHome, "bin").mkdirs()
        File(javaHome, "bin/java").apply {
            writeText("#!/bin/sh\nfor arg in \"\$@\"; do echo \"\$arg\"; done\n")
            setExecutable(true)
        }
    }

    private fun jvmArgs(env: Map<String, String> = emptyMap()): List<String> {
        val output = File(tempDir, "args.out")
        val exit =
            ProcessBuilder("sh", startScript.absolutePath, "version")
                .redirectErrorStream(true)
                .redirectOutput(output)
                .also { pb ->
                    val environment = pb.environment()
                    environment.keys.removeIf { it.startsWith("OTEL_") || it in USER_JVM_OPTS }
                    environment["JAVA_HOME"] = javaHome.absolutePath
                    environment.putAll(env)
                }.start()
                .waitFor()
        check(exit == 0) { "start script exited $exit: ${output.readText()}" }
        return output.readLines()
    }

    @Test
    fun `with no collector configured the agent loads but exports nothing and logs nothing`() {
        val args = jvmArgs()

        assertThat(args).anyMatch { it.startsWith("-javaagent:") && it.endsWith("/opentelemetry-javaagent.jar") }
        assertThat(args).containsAll(EXPORTERS_OFF).contains(AGENT_LOGGING_OFF)
    }

    @Test
    fun `class-data sharing is off so the appended agent does not print a CDS warning on every run`() {
        val args = jvmArgs()

        assertThat(args).contains("-Xshare:off")
        assertThat(args.indexOf("-Xshare:off")).isLessThan(args.indexOfFirst { it.startsWith("-javaagent:") })
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "OTEL_EXPORTER_OTLP_ENDPOINT",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
            "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
            "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT",
            "OTEL_TRACES_EXPORTER",
            "OTEL_METRICS_EXPORTER",
            "OTEL_LOGS_EXPORTER",
        ],
    )
    fun `a configured endpoint or exporter leaves the agent's export settings to the user`(variable: String) {
        val args = jvmArgs(mapOf(variable to "http://collector:4318"))

        assertThat(args).anyMatch { it.startsWith("-javaagent:") }
        assertThat(args).noneMatch { it.startsWith("-Dotel.") }
    }

    @Test
    fun `an agent logging mode the user chose is not overridden`() {
        val args = jvmArgs(mapOf("OTEL_JAVAAGENT_LOGGING" to "simple"))

        assertThat(args).containsAll(EXPORTERS_OFF)
        assertThat(args).noneMatch { it.startsWith("-Dotel.javaagent.logging=") }
    }

    private companion object {
        const val START_SCRIPT_PROPERTY = "easydblab.startScript"
        val USER_JVM_OPTS = setOf("JAVA_OPTS", "EASY_DB_LAB_OPTS")
        val EXPORTERS_OFF =
            listOf("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none")
        const val AGENT_LOGGING_OFF = "-Dotel.javaagent.logging=none"
    }
}
