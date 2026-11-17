package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.LabelSelector
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * [LabelSelectorMatcher] follows the API server's selector rules, so the rollout wait picks the same
 * pods out of one namespace list as a per-workload filtered list would return. One row per rule.
 */
class LabelSelectorMatcherTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `matches a selector with the API server's rules`(
        case: String,
        selector: LabelSelector,
        labels: Map<String, String>?,
        expected: Boolean,
    ) {
        assertThat(LabelSelectorMatcher.matches(selector, labels)).describedAs(case).isEqualTo(expected)
    }

    companion object {
        private val pod = mapOf("app" to "otel", "tier" to "agent")

        private fun labels(vararg entries: Pair<String, String>) = LabelSelectorBuilder().addToMatchLabels(mapOf(*entries)).build()

        private fun expression(
            key: String,
            operator: String,
            vararg values: String,
        ) = LabelSelectorBuilder()
            .addNewMatchExpression()
            .withKey(key)
            .withOperator(operator)
            .withValues(*values)
            .endMatchExpression()
            .build()

        @JvmStatic
        fun cases(): Stream<Arguments> =
            Stream.of(
                Arguments.of("empty selector matches all", LabelSelector(), pod, true),
                Arguments.of("empty selector matches no labels", LabelSelector(), null, true),
                Arguments.of("matchLabels all present", labels("app" to "otel", "tier" to "agent"), pod, true),
                Arguments.of("matchLabels value differs", labels("app" to "fluent"), pod, false),
                Arguments.of("matchLabels key missing", labels("zone" to "a"), pod, false),
                Arguments.of("matchLabels on a pod with no labels", labels("app" to "otel"), null, false),
                Arguments.of("In holds", expression("app", "In", "otel", "beyla"), pod, true),
                Arguments.of("In fails", expression("app", "In", "beyla"), pod, false),
                Arguments.of("In on a missing key", expression("zone", "In", "a"), pod, false),
                Arguments.of("NotIn holds", expression("app", "NotIn", "beyla"), pod, true),
                Arguments.of("NotIn fails", expression("app", "NotIn", "otel"), pod, false),
                Arguments.of("NotIn on a missing key", expression("zone", "NotIn", "a"), pod, true),
                Arguments.of("Exists holds", expression("tier", "Exists"), pod, true),
                Arguments.of("Exists fails", expression("zone", "Exists"), pod, false),
                Arguments.of("DoesNotExist holds", expression("zone", "DoesNotExist"), pod, true),
                Arguments.of("DoesNotExist fails", expression("tier", "DoesNotExist"), pod, false),
                Arguments.of("unknown operator matches nothing", expression("app", "Gt", "1"), pod, false),
            )
    }
}
