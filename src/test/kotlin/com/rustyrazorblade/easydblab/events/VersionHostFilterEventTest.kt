package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The `use` and `install` version events name the host filter they ran with. A blank filter targets
 * every host, so both events must say "all hosts" instead of printing an empty filter.
 */
class VersionHostFilterEventTest {
    @ParameterizedTest
    @CsvSource(
        "'', all hosts",
        "db0, db0",
        "'db0,db2', 'db0,db2'",
    )
    fun `both version events describe the host filter the same way`(
        filter: String,
        expected: String,
    ) {
        val using = Event.Cassandra.UsingVersion("5.0", 3, filter).toDisplayString()
        val installing = Event.Cassandra.InstallingVersion("5.0", 3, filter).toDisplayString()

        assertThat(using).endsWith("filter: $expected")
        assertThat(installing).endsWith("filter: $expected")
    }
}
