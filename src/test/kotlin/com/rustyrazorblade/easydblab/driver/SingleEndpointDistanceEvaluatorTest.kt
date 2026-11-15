package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.api.core.loadbalancing.NodeDistance
import com.datastax.oss.driver.api.core.metadata.EndPoint
import com.datastax.oss.driver.api.core.metadata.Node
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetSocketAddress

class SingleEndpointDistanceEvaluatorTest {
    private val forward = InetSocketAddress("127.0.0.1", 24242)
    private val evaluator = SingleEndpointDistanceEvaluator(forward)

    private fun nodeAt(address: InetSocketAddress): Node {
        val endPoint = mock<EndPoint>()
        whenever(endPoint.resolve()).thenReturn(address)
        return mock<Node>().also { whenever(it.endPoint).thenReturn(endPoint) }
    }

    @Test
    fun `a peer the driver discovers at its pod address is ignored`() {
        assertThat(evaluator.evaluateDistance(nodeAt(InetSocketAddress("10.42.1.7", 30942)), "dc1"))
            .isEqualTo(NodeDistance.IGNORED)
    }

    @Test
    fun `a node on the forward's port at another address is ignored`() {
        assertThat(evaluator.evaluateDistance(nodeAt(InetSocketAddress("10.42.1.8", 24242)), "dc1"))
            .isEqualTo(NodeDistance.IGNORED)
    }

    @Test
    fun `the forwarded node is left to the load-balancing policy`() {
        assertThat(evaluator.evaluateDistance(nodeAt(InetSocketAddress("127.0.0.1", 24242)), "dc1")).isNull()
    }
}
