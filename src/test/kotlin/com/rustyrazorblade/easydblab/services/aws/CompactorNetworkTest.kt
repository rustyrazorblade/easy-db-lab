package com.rustyrazorblade.easydblab.services.aws

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.ec2.model.AvailabilityZone

/**
 * Which zone the compactor's subnet goes in: ECS places the task only in its service's subnets, so
 * a subnet in a zone without Fargate leaves the service unable to start a task.
 */
class CompactorNetworkTest {
    private fun zone(
        name: String,
        id: String,
    ) = AvailabilityZone
        .builder()
        .zoneName(name)
        .zoneId(id)
        .build()

    @Test
    fun `a zone without Fargate is never chosen, even when it sorts first`() {
        // In this account us-east-1a maps to use1-az3, which has no Fargate.
        val zones = listOf(zone("us-east-1b", "use1-az1"), zone("us-east-1a", "use1-az3"), zone("us-east-1c", "use1-az2"))

        assertThat(CompactorNetwork.fargateZone(zones)).isEqualTo("us-east-1b")
    }

    @Test
    fun `the first zone by name is chosen, so every up picks the same one`() {
        val zones = listOf(zone("eu-west-1c", "euw1-az3"), zone("eu-west-1a", "euw1-az1"), zone("eu-west-1b", "euw1-az2"))

        assertThat(CompactorNetwork.fargateZone(zones)).isEqualTo("eu-west-1a")
    }

    @Test
    fun `a region with no Fargate zone is refused, naming its zones`() {
        assertThatThrownBy { CompactorNetwork.fargateZone(listOf(zone("us-east-1a", "use1-az3"))) }
            .hasMessageContaining("us-east-1a")
    }
}
