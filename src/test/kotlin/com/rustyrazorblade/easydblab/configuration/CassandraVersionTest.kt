package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.nio.file.Paths

class CassandraVersionTest {
    private val mainFilePath = Paths.get("packer/cassandra/cassandra_versions.yaml")
    private val extrasDirectoryPath =
        Paths.get(
            "src/test/resources/com/rustyrazorblade/easydblab/configuration/extra_versions",
        )

    @Test
    fun testLoadFromMainAndExtras_ValidYaml() {
        val cassandraVersions = CassandraVersion.loadFromMainAndExtras(mainFilePath, extrasDirectoryPath)
        assertThat(cassandraVersions).isNotEmpty
        assertThat(cassandraVersions).anyMatch { it.version == "3.0" }
        assertThat(cassandraVersions).anyMatch { it.version == "3.11" }
        assertThat(cassandraVersions).anyMatch { it.version == "4.0" }
        assertThat(cassandraVersions).anyMatch { it.version == "1.2" }
    }

    @Test
    fun `lazy round-trips through write and loadFromFile`(
        @TempDir tempDir: Path,
    ) {
        val versions =
            listOf(
                CassandraVersion(version = "5.1-lazy", java = "17", python = "3.11.9", jvmOptions = null, lazy = true),
                CassandraVersion(version = "5.0", java = "11", python = "3.11.9", jvmOptions = null),
            )
        val file = tempDir.resolve("cassandra_versions.yaml").toFile()

        CassandraVersion.write(versions, file)
        val loaded = CassandraVersion.loadFromFile(file.toPath())

        assertThat(loaded.single { it.version == "5.1-lazy" }.lazy).isTrue()
        assertThat(loaded.single { it.version == "5.0" }.lazy).isFalse()
        // A non-lazy entry must not gain a `lazy: false` line when a node's file is rewritten.
        assertThat(file.readText()).doesNotContain("lazy: false")
    }

    @Test
    fun `lazy defaults to false when the field is absent`(
        @TempDir tempDir: Path,
    ) {
        val file = tempDir.resolve("cassandra_versions.yaml").toFile()
        file.writeText(
            """
            - version: "5.0"
              java: "11"
              python: "3.11.9"
            """.trimIndent(),
        )

        assertThat(CassandraVersion.loadFromFile(file.toPath()).single().lazy).isFalse()
    }

    @Test
    fun testYamlDoesNotHaveNulls() {
        val cassandraVersions = CassandraVersion.loadFromMainAndExtras(mainFilePath, extrasDirectoryPath)
        val output = ByteArrayOutputStream()
        CassandraVersion.write(cassandraVersions, output)
        assertThat(output).matches { !it.toString().contains("null") }
    }
}
