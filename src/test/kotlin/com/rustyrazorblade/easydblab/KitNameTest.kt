package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * [listsExactly] matches a typed kit name against the workspace listing with case, so the match
 * does not depend on whether the file system folds case. It runs the same on every OS.
 */
class KitNameTest {
    private val listing = setOf("kafka", "clickhouse-bench", "profiles")

    @ParameterizedTest(name = "[{0}] listed: {1}")
    @CsvSource(
        "kafka, true",
        "clickhouse-bench, true",
        "KAFKA, false",
        "Kafka, false",
        "kafk, false",
        "kafka2, false",
        "CLICKHOUSE-BENCH, false",
    )
    fun `a name is listed only when it matches an entry exactly, case included`(
        name: String,
        listed: Boolean,
    ) {
        assertThat(listsExactly(listing, name)).isEqualTo(listed)
    }
}
