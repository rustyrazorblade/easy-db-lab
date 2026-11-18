package com.rustyrazorblade.easydblab.configuration.environment

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting

/**
 * A [ClusterContractSource] for tests that vary the capabilities and the hosting on their own, which
 * no single recorded environment would. It starts from the contract a default EC2 + K3s state resolves
 * to and replaces only what the test sets, reading [capabilities] and [hosting] on every call.
 */
fun fixedContract(
    capabilities: () -> NodeCapabilities = { NodeCapabilities.AWS },
    hosting: () -> ObservabilityHosting = { ObservabilityHosting.AWS },
): ClusterContractSource =
    ClusterContractSource {
        ClusterContract
            .of(ClusterState(name = "test", versions = mutableMapOf()))
            .copy(nodeCapabilities = capabilities(), hosting = hosting())
    }
