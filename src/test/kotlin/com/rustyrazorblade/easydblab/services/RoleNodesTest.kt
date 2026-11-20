package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RoleNodesTest {
    private val k8sService: K8sService = mock()
    private val roleNodes = RoleNodes(k8sService)

    @Test
    fun `each role's pool is the nodes matching its selector, read from the Kubernetes API`() {
        whenever(k8sService.nodesMatching(eq(mapOf("type" to "db")))).thenReturn(Result.success(listOf("n1", "n2", "n3")))
        whenever(k8sService.nodesMatching(eq(mapOf("type" to "app")))).thenReturn(Result.success(listOf("n4")))

        val placement = roleNodes.placement(RoleSelectors.OWNED_NODES)

        assertThat(placement.dbNodeCount).isEqualTo(3)
        assertThat(placement.appNodeCount).isEqualTo(1)
        assertThat(placement.selectors).isEqualTo(RoleSelectors.OWNED_NODES)
    }

    @Test
    fun `an unconstrained role's pool is every node`() {
        whenever(k8sService.nodesMatching(eq(emptyMap()))).thenReturn(Result.success(listOf("only")))

        val placement = roleNodes.placement(RoleSelectors.ANYWHERE)

        assertThat(placement.dbNodeCount).isEqualTo(1)
        assertThat(placement.appNodeCount).isEqualTo(1)
    }

    @Test
    fun `a failed node listing fails naming the selector`() {
        whenever(k8sService.nodesMatching(any())).thenReturn(Result.failure(IllegalStateException("refused")))

        assertThatThrownBy { roleNodes.count(NodeSelector(mapOf("pool" to "databases"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("pool=databases")
            .hasRootCauseMessage("refused")
    }
}
