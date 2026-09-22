package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Runs the dev wrapper `bin/easy-db-lab` with `EASY_DB_LAB_USE_DOCKER=1` against a stub `docker`
 * and a stub generated launcher, to check the OTLP settings it hands the agent.
 *
 * The dev collector in `docker-compose.yml` publishes both OTLP ports on random host ports. The
 * agent's default protocol is `http/protobuf`, which only the 4318 receiver speaks; pointing it at
 * the published 4317 (gRPC) port without switching protocol means no telemetry ever arrives.
 */
class DevLauncherTelemetryTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var appHome: File
    private lateinit var stubBin: File

    @BeforeEach
    fun layOutAppHome() {
        appHome = File(tempDir, "app")
        File(appHome, "bin").mkdirs()
        File(DEV_LAUNCHER).copyTo(File(appHome, "bin/easy-db-lab")).setExecutable(true)
        File(appHome, "docker-compose.yml").writeText("services: {}\n")
        executable(
            File(appHome, "build/install/easy-db-lab/bin/easy-db-lab"),
            "#!/bin/sh\nenv | grep '^OTEL_' | sort\n",
        )
        stubBin = File(tempDir, "stub-bin")
        executable(
            File(stubBin, "docker"),
            """
            |#!/bin/sh
            |case "${'$'}*" in
            |  *" port otel-collector 4317") echo "0.0.0.0:$GRPC_HOST_PORT" ;;
            |  *" port otel-collector 4318") echo "0.0.0.0:$HTTP_HOST_PORT" ;;
            |esac
            |exit 0
            |
            """.trimMargin(),
        )
    }

    private fun executable(
        file: File,
        body: String,
    ) {
        file.parentFile.mkdirs()
        file.writeText(body)
        file.setExecutable(true)
    }

    private fun otelEnvWithDocker(): Map<String, String> {
        val output = File(tempDir, "env.out")
        val exit =
            ProcessBuilder("bash", File(appHome, "bin/easy-db-lab").absolutePath, "version")
                .directory(tempDir)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .also { pb ->
                    val env = pb.environment()
                    env.keys.removeIf { it.startsWith("OTEL_") || it.startsWith("EASY_DB_LAB_") }
                    env["PATH"] = "${stubBin.absolutePath}:${env["PATH"]}"
                    env["EASY_DB_LAB_USE_DOCKER"] = "1"
                    env["EASY_DB_LAB_LOG_DIR"] = File(tempDir, "logs").absolutePath
                }.start()
                .waitFor()
        check(exit == 0) { "bin/easy-db-lab exited $exit: ${output.readText()}" }
        return output
            .readLines()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    @Test
    fun `docker mode points the agent's http-protobuf exporter at the collector's published 4318 port`() {
        val env = otelEnvWithDocker()

        assertThat(env["OTEL_EXPORTER_OTLP_ENDPOINT"]).isEqualTo("http://localhost:$HTTP_HOST_PORT")
        assertThat(env["OTEL_EXPORTER_OTLP_PROTOCOL"] ?: "http/protobuf").isEqualTo("http/protobuf")
    }

    private companion object {
        const val DEV_LAUNCHER = "bin/easy-db-lab"
        const val GRPC_HOST_PORT = 49317
        const val HTTP_HOST_PORT = 49318
    }
}
