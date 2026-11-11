package com.rustyrazorblade.easydblab.services.aws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * When `down` stops the account compactor: only once no cluster VPC that names the account bucket
 * is left, in any region, after the VPCs this `down` tore down are left out.
 */
class CompactorShutdownPolicyTest {
    @Test
    fun `only this cluster's VPCs left in the census stops the compactor`() {
        val census = listOf(ClusterVpc("us-west-2", "vpc-this"))

        assertThat(CompactorShutdownPolicy.decide(census, tornDown = setOf("vpc-this")))
            .isEqualTo(CompactorShutdownPolicy.Decision.Stop)
    }

    @Test
    fun `another cluster in another region keeps the compactor running`() {
        val other = ClusterVpc("eu-west-1", "vpc-other")
        val census = listOf(ClusterVpc("us-west-2", "vpc-this"), other)

        assertThat(CompactorShutdownPolicy.decide(census, tornDown = setOf("vpc-this")))
            .isEqualTo(CompactorShutdownPolicy.Decision.Keep(listOf(other)))
    }
}
