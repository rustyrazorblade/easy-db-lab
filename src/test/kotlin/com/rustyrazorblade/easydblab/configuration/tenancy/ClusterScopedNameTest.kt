package com.rustyrazorblade.easydblab.configuration.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A cluster-scoped name is `<base>-<clusterId>`, and never longer than the 253 characters Kubernetes
 * allows a ClusterRole or ClusterRoleBinding name: a longer one fails here, naming it, instead of at
 * the API server partway through a deploy.
 */
class ClusterScopedNameTest {
    @Test
    fun `appends the full cluster id to the base`() {
        assertThat(ClusterScopedName.of("otel-collector", "id-1")).isEqualTo("otel-collector-id-1")
    }

    @Test
    fun `accepts a name of exactly 253 characters`() {
        val name = ClusterScopedName.of("a".repeat(248), "id-1")

        assertThat(name).hasSize(253)
    }

    @Test
    fun `refuses a name of 254 characters, naming the base, the length and the limit`() {
        assertThatThrownBy { ClusterScopedName.of("a".repeat(249), "id-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("a".repeat(249))
            .hasMessageContaining("254")
            .hasMessageContaining("253")
    }
}
