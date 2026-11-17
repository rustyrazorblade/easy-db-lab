package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.bringup.k3s.Fabric8ControlPlaneReader.Replicas
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The Cilium conditions the K3s reader gives the gate: the operator is ready only with every
 * desired replica ready (and some desired), and each required CRD counts only once Established.
 */
class CiliumGateConditionsTest {
    private val operator = "${Constants.Cilium.OPERATOR_DEPLOYMENT} ready"
    private val crds = Constants.Cilium.REQUIRED_CRDS

    @ParameterizedTest(name = "ready {0} of desired {1} -> {2}")
    @CsvSource("1, 1, true", "2, 2, true", "3, 2, true", "0, 1, false", "1, 2, false", "0, 0, false")
    fun `the operator is ready only when every desired replica is`(
        ready: Int,
        desired: Int,
        expected: Boolean,
    ) {
        val conditions = Fabric8ControlPlaneReader.ciliumConditions(Replicas(ready, desired), crds.toSet())

        assertThat(conditions[operator]).isEqualTo(expected)
    }

    @Test
    fun `a missing operator Deployment is not ready`() {
        assertThat(Fabric8ControlPlaneReader.ciliumConditions(null, crds.toSet())[operator]).isFalse()
    }

    @Test
    fun `each required CRD is its own condition, true only once Established`() {
        // Every required CRD but the first two is Established, plus one the agent does not need.
        val established = crds.drop(2).toSet() + "other.example.com"

        val conditions = Fabric8ControlPlaneReader.ciliumConditions(Replicas(1, 1), established)

        assertThat(conditions.keys).containsExactlyElementsOf(listOf(operator) + crds.map { "CRD $it established" })
        assertThat(conditions.filterValues { !it }.keys).containsExactly("CRD ${crds[0]} established", "CRD ${crds[1]} established")
    }
}
