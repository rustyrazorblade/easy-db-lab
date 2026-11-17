package com.rustyrazorblade.easydblab.providers.aws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * The decision `down <vpc-id>` takes with no cluster state: a VPC is easy-db-lab's to delete only
 * when it carries both the easy-db-lab marker tag and a ClusterId tag.
 */
class VpcOwnershipTest {
    @ParameterizedTest(name = "{0} -> created by easy-db-lab = {2}")
    @MethodSource("vpcTags")
    fun `classifies a VPC by its tags`(
        description: String,
        tags: Map<String, String>,
        expected: Boolean,
    ) {
        assertThat(VpcOwnership.createdByEasyDbLab(tags)).describedAs(description).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun vpcTags(): Stream<Arguments> =
            Stream.of(
                Arguments.of("no tags", emptyMap<String, String>(), false),
                Arguments.of("only a Name", mapOf("Name" to "shared"), false),
                Arguments.of("marker without ClusterId", mapOf("easy_cass_lab" to "1"), false),
                Arguments.of("ClusterId without marker", mapOf("ClusterId" to "c-1"), false),
                Arguments.of("marker with another value", mapOf("easy_cass_lab" to "0", "ClusterId" to "c-1"), false),
                Arguments.of("marker and blank ClusterId", mapOf("easy_cass_lab" to "1", "ClusterId" to " "), false),
                Arguments.of("marker and ClusterId", mapOf("easy_cass_lab" to "1", "ClusterId" to "c-1", "Name" to "x"), true),
            )
    }
}
