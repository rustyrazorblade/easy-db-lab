package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RoleNodesTest {
    private val k8sService: K8sService = mock()
    private val roleNodes = RoleNodes(k8sService)

    @BeforeEach
    fun noAllocatableByDefault() {
        whenever(k8sService.nodeAllocatable(any())).thenReturn(Result.success(emptyList()))
    }

    @Test
    fun `each role's pool is the nodes matching its selector, read from the Kubernetes API`() {
        whenever(k8sService.nodesMatching(eq(mapOf("type" to "db")))).thenReturn(Result.success(listOf("n1", "n2", "n3")))
        whenever(k8sService.nodesMatching(eq(mapOf("type" to "app")))).thenReturn(Result.success(listOf("n4")))

        val placement = roleNodes.placement(ClusterState(name = "c", roleSelectors = RoleSelectors.OWNED_NODES))

        assertThat(placement.dbNodeCount).isEqualTo(3)
        assertThat(placement.appNodeCount).isEqualTo(1)
        assertThat(placement.selectors).isEqualTo(RoleSelectors.OWNED_NODES)
    }

    @Test
    fun `an unconstrained role's pool is every node`() {
        whenever(k8sService.nodesMatching(eq(emptyMap()))).thenReturn(Result.success(listOf("only")))

        val placement = roleNodes.placement(ClusterState(name = "c", roleSelectors = RoleSelectors.ANYWHERE))

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

    @Test
    fun `an owned cluster publishes the smallest db node's allocatable resources and Guaranteed pods`() {
        whenever(k8sService.nodesMatching(any())).thenReturn(Result.success(listOf("n1", "n2")))
        whenever(k8sService.nodeAllocatable(eq(mapOf("type" to "db"))))
            .thenReturn(Result.success(listOf(NodeAllocatable(30000, 8), NodeAllocatable(31000, 8))))

        val vars = roleNodes.placement(ClusterState(name = "c", environment = Environment.Ec2K3s)).toMap()

        assertThat(vars).containsAllEntriesOf(
            mapOf("DB_NODE_ALLOCATABLE_MIB" to "30000", "DB_NODE_ALLOCATABLE_CPU" to "8", "GUARANTEED_PODS" to "true"),
        )
    }

    @Test
    fun `a brought or local cluster does not ask for Guaranteed pods`() {
        whenever(k8sService.nodesMatching(any())).thenReturn(Result.success(listOf("n1")))
        whenever(k8sService.nodeAllocatable(any())).thenReturn(Result.success(listOf(NodeAllocatable(8000, 4))))

        val brought = roleNodes.placement(ClusterState(name = "c", environment = Environment.Kubeconfig("/k"))).toMap()
        val local = roleNodes.placement(ClusterState(name = "c", environment = Environment.K3sDocker("/d"))).toMap()

        assertThat(brought["GUARANTEED_PODS"]).isEqualTo("false")
        assertThat(local["GUARANTEED_PODS"]).isEqualTo("false")
        assertThat(brought["DB_NODE_ALLOCATABLE_MIB"]).isEqualTo("8000")
    }

    @Test
    fun `a failed allocatable read fails naming the db selector`() {
        whenever(k8sService.nodesMatching(any())).thenReturn(Result.success(listOf("n1")))
        whenever(k8sService.nodeAllocatable(any())).thenReturn(Result.failure(IllegalStateException("refused")))

        assertThatThrownBy { roleNodes.placement(ClusterState(name = "c")) }
            .hasMessageContaining("type=db")
            .hasRootCauseMessage("refused")
    }
}
