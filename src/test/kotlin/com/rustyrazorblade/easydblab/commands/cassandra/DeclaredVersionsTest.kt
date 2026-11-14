package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Context
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeclaredVersionsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `reads the packaged versions file`() {
        val packer = File(tempDir, "packer")
        File(packer, "cassandra").mkdirs()
        File(packer, "cassandra/cassandra_versions.yaml").writeText(
            """
            - version: "5.0"
              java: "17"
              python: "3.11.9"
            """.trimIndent(),
        )

        val versions = declaredCassandraVersions(packer, File(tempDir, "extras"))

        assertThat(versions.map { it.version }).containsExactly("5.0")
    }

    @Test
    fun `merges the profile extras with the packaged versions`() {
        val packer = File(tempDir, "packer")
        File(packer, "cassandra").mkdirs()
        File(packer, "cassandra/cassandra_versions.yaml").writeText("- version: \"5.0\"\n  java: \"17\"\n  python: \"3.11.9\"\n")
        val extras = File(tempDir, "extras").also { it.mkdirs() }
        File(extras, "mine.yaml").writeText("- version: \"cep-45\"\n  java: \"17\"\n  python: \"3.11.9\"\n")

        val versions = declaredCassandraVersions(packer, extras)

        assertThat(versions.map { it.version }).containsExactlyInAnyOrder("5.0", "cep-45")
    }

    @Test
    fun `the context overload reads packer under the install directory`() {
        val appHome = File(tempDir, "app")
        File(appHome, "packer/cassandra").mkdirs()
        File(appHome, "packer/cassandra/cassandra_versions.yaml").writeText("- version: \"4.1\"\n  java: \"11\"\n  python: \"3.11.9\"\n")
        val context = Context(File(tempDir, "user"), appHome = appHome.path)

        assertThat(declaredCassandraVersions(context).map { it.version }).containsExactly("4.1")
    }

    @Test
    fun `the context overload names the missing system property when the install directory is unknown`() {
        val context = Context(File(tempDir, "user"), appHome = null)

        assertThatThrownBy { declaredCassandraVersions(context) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("easydblab.apphome")
    }

    @Test
    fun `fails loudly naming the missing file when packer is not installed`() {
        val packer = File(tempDir, "packer")

        assertThatThrownBy { declaredCassandraVersions(packer, File(tempDir, "extras")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(File(packer, "cassandra/cassandra_versions.yaml").path)
    }
}
