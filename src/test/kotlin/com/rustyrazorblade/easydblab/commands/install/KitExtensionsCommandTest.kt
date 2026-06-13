package com.rustyrazorblade.easydblab.commands.install

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class KitExtensionsCommandTest {
    @TempDir
    lateinit var tempDir: File

    private fun captureOutput(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(baos))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return baos.toString()
    }

    @Test
    fun `output contains all three built-in alias names`() {
        val extensionsFile = File(tempDir, "extensions.yaml")
        extensionsFile.writeText(
            """
            extensions:
              duckdb:
                description: "DuckDB"
                image: "ghcr.io/duckdb/pg_duckdb:pg__PG_MAJOR__"
                create_extensions:
                  - pg_duckdb
              postgis:
                description: "PostGIS"
                image: "ghcr.io/cloudnative-pg/postgis:__PG_MAJOR__"
                create_extensions:
                  - postgis
              timescaledb:
                description: "TimescaleDB"
                image: "timescale/timescaledb-ha:pg__PG_MAJOR__-latest"
                create_extensions:
                  - timescaledb
            """.trimIndent(),
        )
        val command = KitExtensionsCommand(tempDir)
        val output = captureOutput { command.call() }
        assertThat(output).contains("duckdb")
        assertThat(output).contains("postgis")
        assertThat(output).contains("timescaledb")
    }

    @Test
    fun `output shows no aliases message when extensions yaml missing`() {
        val command = KitExtensionsCommand(tempDir)
        val output = captureOutput { command.call() }
        assertThat(output).contains("No extension aliases found")
    }
}
