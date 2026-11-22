package com.rustyrazorblade.easydblab.configuration.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [K8sNamespace] is the only place the namespace rule lives: a DNS-1123 label of at most 63
 * characters that is not one of Kubernetes' own system namespaces.
 */
class K8sNamespaceTest {
    @ParameterizedTest
    @ValueSource(strings = ["default", "lab-a", "a", "edl-ns-2", "0lab"])
    fun `accepts a DNS-1123 label`(name: String) {
        assertThat(K8sNamespace(name).value).isEqualTo(name)
    }

    @Test
    fun `accepts a name of 63 characters`() {
        val name = "a".repeat(63)

        assertThat(K8sNamespace(name).value).isEqualTo(name)
    }

    @Test
    fun `refuses a name of 64 characters`() {
        assertThatThrownBy { K8sNamespace("a".repeat(64)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("63")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Lab_A", "LabA", "lab_a", "-lab", "lab-", "", "lab.a"])
    fun `refuses a name that is not a DNS-1123 label`(name: String) {
        assertThatThrownBy { K8sNamespace(name) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'$name'")
            .hasMessageContaining("DNS-1123")
    }

    @ParameterizedTest
    @ValueSource(strings = ["kube-system", "kube-public", "kube-node-lease"])
    fun `refuses a Kubernetes system namespace`(name: String) {
        assertThatThrownBy { K8sNamespace(name) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'$name'")
            .hasMessageContaining("reserved")
    }
}
