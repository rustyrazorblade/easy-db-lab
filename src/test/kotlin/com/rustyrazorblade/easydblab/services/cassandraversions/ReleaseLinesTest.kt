package com.rustyrazorblade.easydblab.services.cassandraversions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The release-line table maps a version name to the entry whose JDK list it uses: the longest
 * entry that is a prefix of the name at a `-` boundary or the end, else the leading `X.Y`.
 */
class ReleaseLinesTest {
    private val table = ReleaseLines.load()

    @ParameterizedTest(name = "{0} maps to {1}")
    @CsvSource(
        "4.1.7, 4.1",
        "4.1, 4.1",
        "4.0-HEAD, 4.0",
        "5.0-HEAD, 5.0",
        "5.0.6, 5.0",
        "5.0, 5.0",
        "5.0-rc1, 5.0",
        "5.0-rustyrazorblade-HEAD, 5.0-rustyrazorblade",
        "5.0-rustyrazorblade, 5.0-rustyrazorblade",
        "5.0-rustyrazorbladeX, 5.0",
        "6.0-rustyrazorblade-HEAD, 6.0",
        "3.11.17, 3.11",
        "3.0.31, 3.0",
        "trunk, trunk",
        "trunk-HEAD, trunk",
    )
    fun `a name maps to the longest entry at a boundary`(
        name: String,
        entry: String,
    ) {
        assertThat(table.entryFor(name)?.name).isEqualTo(entry)
    }

    @ParameterizedTest(name = "{0} maps to nothing")
    @CsvSource("5.1-HEAD", "5.1.0", "trunkX", "my-build", "50.0")
    fun `a name of no known line maps to nothing`(name: String) {
        assertThat(table.entryFor(name)).isNull()
    }

    @Test
    fun `each line lists the JDKs the design table gives`() {
        val jdks = table.lines.associate { it.name to it.javaSupported }

        assertThat(jdks).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "3.0" to listOf("8"),
                "3.11" to listOf("8"),
                "4.0" to listOf("8", "11"),
                "4.1" to listOf("8", "11"),
                "5.0" to listOf("11", "17"),
                "5.0-rustyrazorblade" to listOf("11", "17", "21"),
                "6.0" to listOf("11", "17", "21"),
                "trunk" to listOf("11", "17", "21"),
            ),
        )
    }

    @Test
    fun `4_0 and 4_1 git builds on JDK 11 get the jdk11 ant flag, and nothing else does`() {
        val flagged =
            table.lines.flatMap { line -> line.javaSupported.mapNotNull { jdk -> line.antFlagsFor(jdk)?.let { "${line.name}/$jdk=$it" } } }

        assertThat(flagged).containsExactlyInAnyOrder("4.0/11=-Duse.jdk11=true", "4.1/11=-Duse.jdk11=true")
    }

    @Test
    fun `an entry whose ant flags name a JDK it does not support is refused`() {
        val yaml =
            """
            lines:
              - name: "4.1"
                java_supported: ["8", "11"]
                ant_flags: {"17": "-Dx=y"}
            """.trimIndent()

        assertThatThrownBy { ReleaseLines.parse(yaml) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("4.1", "17")
    }
}
