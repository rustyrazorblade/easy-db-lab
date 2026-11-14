package com.rustyrazorblade.easydblab.configuration.store

import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A store prefix goes into IAM resource ARNs (the compactor's allow) and into every key, so one
 * that holds an IAM wildcard, climbs with `..`, or has a root as a segment could widen a policy onto data
 * it must not reach. Every place a prefix enters the store model refuses those.
 */
class StorePrefixTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "team-a", "team_a.v2", "a/b", "/team-a/", "grafana-x", "Team-A", "v.1"])
    fun `a prefix of plain segments is accepted`(prefix: String) {
        assertThat(S3Backend(bucket = "b", prefix = prefix, region = "r").prefix).isEqualTo(prefix)
        assertThat(ObservabilityStore("b", "t", prefix).storePrefix).isEqualTo(prefix)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "*", "team-*", "te?m", "..", "a/../b", "a/./b", "a//b", "a b", "a\$b",
            "mimir", "loki", "tempo", "grafana", "reports", "pyroscope", "grafana/x", "/mimir/", "x/grafana", "a/loki/b", ".", "a/.",
        ],
    )
    fun `a prefix that could widen a policy is refused`(prefix: String) {
        assertThatThrownBy { S3Backend(bucket = "b", prefix = prefix, region = "r") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(prefix)
        assertThatThrownBy { ObservabilityStore("b", "t", prefix) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
