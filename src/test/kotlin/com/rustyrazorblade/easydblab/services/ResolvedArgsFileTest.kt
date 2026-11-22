package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The kit's recorded install args: read as the runner reads them, and one arg rewritten in place. */
class ResolvedArgsFileTest {
    @TempDir
    lateinit var kitDir: File

    private val file get() = File(kitDir, Constants.Kit.RESOLVED_ARGS_FILE)

    @Test
    fun `rewriting one arg keeps every other line, and the next read sees the new value`() {
        file.writeText("# written by kit install\nCASSANDRA_VERSION=5.0\nSTORAGE_SIZE=10Gi\nCASSANDRA_DC=dc1\n")

        ResolvedArgsFile(kitDir).rewrite(mapOf("CASSANDRA_VERSION" to "5.0.4"))

        assertThat(file.readText()).isEqualTo("# written by kit install\nCASSANDRA_VERSION=5.0.4\nSTORAGE_SIZE=10Gi\nCASSANDRA_DC=dc1\n")
        assertThat(ResolvedArgsFile(kitDir).read()).containsEntry("CASSANDRA_VERSION", "5.0.4").containsEntry("STORAGE_SIZE", "10Gi")
    }

    @Test
    fun `an arg the file does not hold yet is appended`() {
        file.writeText("STORAGE_SIZE=10Gi\n")

        ResolvedArgsFile(kitDir).rewrite(mapOf("CASSANDRA_BUILD" to "5.0-HEAD"))

        assertThat(ResolvedArgsFile(kitDir).read()).containsExactlyInAnyOrderEntriesOf(
            mapOf("STORAGE_SIZE" to "10Gi", "CASSANDRA_BUILD" to "5.0-HEAD"),
        )
    }

    @Test
    fun `a value with a line break is refused and the file is unchanged`() {
        file.writeText("CASSANDRA_VERSION=5.0\n")

        assertThatThrownBy { ResolvedArgsFile(kitDir).rewrite(mapOf("CASSANDRA_VERSION" to "5.0\nEVIL=1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("CASSANDRA_VERSION")
        assertThat(file.readText()).isEqualTo("CASSANDRA_VERSION=5.0\n")
    }

    @Test
    fun `a kit with no recorded args reads as none`() {
        assertThat(ResolvedArgsFile(kitDir).read()).isEmpty()
    }
}
