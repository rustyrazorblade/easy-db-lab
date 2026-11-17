package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The `use` and `install` version events name the host filter they ran with and where the version
 * comes from. A blank filter targets every host, so both events must say "all hosts" instead of
 * printing an empty filter.
 */
class VersionHostFilterEventTest {
    @ParameterizedTest
    @CsvSource(
        "'', all hosts",
        "db0, db0",
        "'db0,db2', 'db0,db2'",
    )
    fun `both version events describe the host filter and the resolved source the same way`(
        filter: String,
        expected: String,
    ) {
        val url = "https://dlcdn.test/apache-cassandra-5.0.6-bin.tar.gz"
        val using = Event.Cassandra.UsingVersion("5.0", 3, filter, "ASF", url, "17").toDisplayString()
        val installing = Event.Cassandra.InstallingVersion("5.0", 3, filter, "ASF", url, "17").toDisplayString()

        listOf(using, installing).forEach { text ->
            assertThat(text.lines().first()).endsWith("filter: $expected")
            assertThat(text.lines().last()).isEqualTo("  source: ASF, url: $url, JDK 17")
        }
    }
}
