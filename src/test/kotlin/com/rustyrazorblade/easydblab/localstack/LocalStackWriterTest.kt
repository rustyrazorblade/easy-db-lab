package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/** The stack directory contract: what is written, what is refused, and what is never touched. */
class LocalStackWriterTest {
    @TempDir
    lateinit var root: File

    private val spec =
        LocalStackSpec(
            mode = LocalStackSpec.Mode.Live(bucket = "acct", region = "us-west-2", awsProfile = "lab", home = "default"),
            tenants = listOf("acme"),
            grafanaPort = 3300,
        )

    private val files =
        mapOf(
            "compose.yml" to "services: {}\n".toByteArray(),
            "config/mimir.yaml" to "target: all\n".toByteArray(),
        )

    private val writer = LocalStackWriter(toolVersion = "1.2.3")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(dir: File) = LocalStackManifest.read(File(dir, Constants.LocalStack.MANIFEST_FILE))

    @Test
    fun `a missing directory is created with every file and a manifest of them`() {
        val dir = File(root, "lab-local/nested")

        val result = writer.write(dir, spec, files)

        assertThat(File(dir, "compose.yml").readText()).isEqualTo("services: {}\n")
        assertThat(File(dir, "config/mimir.yaml").readText()).isEqualTo("target: all\n")
        assertThat(result.written).containsExactly("compose.yml", "config/mimir.yaml")
        assertThat(result.edited).isEmpty()
        assertThat(manifest(dir)).isEqualTo(
            LocalStackManifest(
                toolVersion = "1.2.3",
                mode = "live",
                homeTenant = "default",
                tenants = listOf("acme", "default"),
                grafanaPort = 3300,
                files = files.mapValues { sha256(it.value) },
            ),
        )
    }

    @Test
    fun `an empty directory is used as it is`() {
        val dir = File(root, "empty").also { it.mkdirs() }

        writer.write(dir, spec, files)

        assertThat(File(dir, "compose.yml")).exists()
    }

    @Test
    fun `a directory that is not empty and holds no manifest is refused before anything is written`() {
        val dir = File(root, "foreign").also { it.mkdirs() }
        File(dir, "notes.txt").writeText("mine")

        assertThatThrownBy { writer.write(dir, spec, files) }
            .isInstanceOf(LocalStackDirectoryRefused::class.java)
            .hasMessageContaining(dir.path)
        assertThat(dir.list()).containsExactly("notes.txt")
        assertThat(File(dir, "notes.txt").readText()).isEqualTo("mine")
    }

    @Test
    fun `a re-run overwrites an owned file the user edited and reports it`() {
        val dir = File(root, "stack")
        writer.write(dir, spec, files)
        File(dir, "config/mimir.yaml").writeText("target: compactor\n")

        val result = writer.write(dir, spec, files)

        assertThat(File(dir, "config/mimir.yaml").readText()).isEqualTo("target: all\n")
        assertThat(result.edited).containsExactly("config/mimir.yaml")
    }

    @Test
    fun `a re-run leaves data, state, Grafana's data and the override byte-identical`() {
        val dir = File(root, "stack")
        writer.write(dir, spec, files)
        val untouched =
            mapOf(
                "compose.override.yml" to "services: {grafana: {ports: [\"127.0.0.1:4000:3000\"]}}\n",
                "data/mimir/acme/block" to "block bytes",
                "cache/mimir/tsdb-sync/x" to "index header",
                "grafana-data/grafana.db" to "sqlite",
                "dashboards/current/infrastructure/a.json" to "{}",
            )
        untouched.forEach { (path, text) -> File(dir, path).also { it.parentFile.mkdirs() }.writeText(text) }

        writer.write(dir, spec.copy(tenants = listOf("acme", "beta")), files)

        untouched.forEach { (path, text) -> assertThat(File(dir, path).readText()).describedAs(path).isEqualTo(text) }
        assertThat(manifest(dir).tenants).containsExactly("acme", "beta", "default")
    }

    @Test
    fun `no owned file may be the override or sit in a data or state directory`() {
        val dir = File(root, "stack")

        for (path in listOf("compose.override.yml", "data/x", "cache/mimir/x", "grafana-data/grafana.db", "../escape", "/etc/passwd")) {
            assertThatThrownBy { writer.write(dir, spec, files + (path to ByteArray(0))) }
                .describedAs(path)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(path)
        }
        assertThat(dir).doesNotExist()
    }
}
