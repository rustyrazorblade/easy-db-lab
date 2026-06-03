package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [interpolateStepVars], which substitutes shell-style
 * `${VAR}` placeholders in step strings and warns on unresolved ones.
 */
class InterpolateStepVarsTest {
    @Test
    fun `resolves single variable`() {
        val result = interpolateStepVars("\${FOO}", mapOf("FOO" to "bar"))
        assertThat(result).isEqualTo("bar")
    }

    @Test
    fun `resolves multiple variables in one string`() {
        val result =
            interpolateStepVars(
                "ns=\${NAMESPACE} kit=\${KIT_NAME}",
                mapOf("NAMESPACE" to "clickhouse", "KIT_NAME" to "clickhouse"),
            )
        assertThat(result).isEqualTo("ns=clickhouse kit=clickhouse")
    }

    @Test
    fun `leaves unresolved variable as original placeholder`() {
        val result = interpolateStepVars("\${MISSING}", emptyMap())
        assertThat(result).isEqualTo("\${MISSING}")
    }

    @Test
    fun `resolves some and leaves others when partially resolved`() {
        val result =
            interpolateStepVars(
                "\${KNOWN} and \${UNKNOWN}",
                mapOf("KNOWN" to "value"),
            )
        assertThat(result).isEqualTo("value and \${UNKNOWN}")
    }

    @Test
    fun `string with no placeholders is returned unchanged`() {
        val input = "helm upgrade --install myrelease mychart"
        assertThat(interpolateStepVars(input, mapOf("UNUSED" to "x"))).isEqualTo(input)
    }

    @Test
    fun `resolves variable embedded in a larger string`() {
        val result =
            interpolateStepVars(
                "s3://\${BUCKET}/prefix",
                mapOf("BUCKET" to "my-bucket"),
            )
        assertThat(result).isEqualTo("s3://my-bucket/prefix")
    }

    @Test
    fun `empty string returns empty string`() {
        assertThat(interpolateStepVars("", mapOf("FOO" to "bar"))).isEqualTo("")
    }
}
