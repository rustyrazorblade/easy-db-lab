package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InterpolateStepVarsTest {
    private val vars = mapOf("CLUSTER_NAME" to "mycluster", "NAMESPACE" to "myns", "VERSION" to "1.2.3")

    @Test
    fun `replaces single variable`() {
        assertThat(interpolateStepVars("\${CLUSTER_NAME}", vars)).isEqualTo("mycluster")
    }

    @Test
    fun `replaces multiple variables in one string`() {
        val result = interpolateStepVars("\${CLUSTER_NAME}/\${NAMESPACE}", vars)
        assertThat(result).isEqualTo("mycluster/myns")
    }

    @Test
    fun `leaves unknown variable token unchanged`() {
        val result = interpolateStepVars("\${UNKNOWN}", vars)
        assertThat(result).isEqualTo("\${UNKNOWN}")
    }

    @Test
    fun `mixes known and unknown variables`() {
        val result = interpolateStepVars("\${CLUSTER_NAME}/\${UNKNOWN}", vars)
        assertThat(result).isEqualTo("mycluster/\${UNKNOWN}")
    }

    @Test
    fun `string with no variables is returned unchanged`() {
        assertThat(interpolateStepVars("helm upgrade --install myapp", vars)).isEqualTo("helm upgrade --install myapp")
    }

    @Test
    fun `empty string is returned unchanged`() {
        assertThat(interpolateStepVars("", vars)).isEqualTo("")
    }

    @Test
    fun `variable at start and end of string`() {
        val result = interpolateStepVars("\${VERSION}-snapshot-\${NAMESPACE}", vars)
        assertThat(result).isEqualTo("1.2.3-snapshot-myns")
    }
}
