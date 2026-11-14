package com.rustyrazorblade.easydblab.configuration.store

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GoldenUpdateTest {
    @Test
    fun `a rewrite is requested only by UPDATE_GOLDEN=true`() {
        assertThat(GoldenUpdate.requested(mapOf("UPDATE_GOLDEN" to "true"))).isTrue()
        assertThat(GoldenUpdate.requested(mapOf("UPDATE_GOLDEN" to "1"))).isFalse()
        assertThat(GoldenUpdate.requested(emptyMap())).isFalse()
    }

    @Test
    fun `CI compares without a rewrite`() {
        assertThat(GoldenUpdate.requested(mapOf("CI" to "true"))).isFalse()
    }

    /** A rewrite in CI would make every golden pass against whatever the code renders. */
    @Test
    fun `a rewrite in CI is refused`() {
        assertThatThrownBy { GoldenUpdate.requested(mapOf("UPDATE_GOLDEN" to "true", "CI" to "true")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("CI")
    }
}
