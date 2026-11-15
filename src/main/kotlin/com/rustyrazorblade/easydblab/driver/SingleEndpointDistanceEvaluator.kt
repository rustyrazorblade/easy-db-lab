package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.api.core.loadbalancing.NodeDistance
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistanceEvaluator
import com.datastax.oss.driver.api.core.metadata.Node
import java.net.InetSocketAddress

/**
 * Keeps the driver on the one Cassandra pod a port-forward reaches.
 *
 * On a cluster reached by port-forward only the forward's loopback address is reachable from this
 * machine; every peer the driver discovers in `system.peers` is a pod address it cannot dial. This
 * marks every node whose endpoint is not [forward] IGNORED, so the driver opens no pool to it, and
 * leaves the forwarded node to the load-balancing policy (a null distance).
 *
 * @property forward the loopback address of the port-forward
 */
data class SingleEndpointDistanceEvaluator(
    private val forward: InetSocketAddress,
) : NodeDistanceEvaluator {
    override fun evaluateDistance(
        node: Node,
        localDc: String?,
    ): NodeDistance? = if (node.endPoint.resolve() == forward) null else NodeDistance.IGNORED
}
