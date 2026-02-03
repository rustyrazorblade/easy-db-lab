package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for InfrastructureConfig factory methods and related configuration classes.
 */
class InfrastructureConfigTest {
    @Nested
    inner class ForCluster {
        @Test
        fun `should create subnet config for each availability zone`() {
            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "test-cluster",
                    availabilityZones = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
                    sshCidrs = listOf("1.2.3.4/32"),
                    sshPort = 22,
                )

            assertThat(config.subnets).hasSize(3)
            assertThat(config.subnets[0].availabilityZone).isEqualTo("us-west-2a")
            assertThat(config.subnets[1].availabilityZone).isEqualTo("us-west-2b")
            assertThat(config.subnets[2].availabilityZone).isEqualTo("us-west-2c")
        }

        @Test
        fun `should include SSH rules for each CIDR`() {
            val sshCidrs = listOf("1.2.3.4/32", "5.6.7.8/32")

            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "test-cluster",
                    availabilityZones = listOf("us-west-2a"),
                    sshCidrs = sshCidrs,
                    sshPort = 22,
                )

            // Should have SSH rules for each CIDR + VPC internal TCP + VPC internal UDP
            val sshRules = config.securityGroupRules.filter { it.fromPort == 22 && it.toPort == 22 }
            assertThat(sshRules).hasSize(2)
            assertThat(sshRules.map { it.cidr }).containsExactlyInAnyOrder("1.2.3.4/32", "5.6.7.8/32")
        }

        @Test
        fun `should include VPC internal traffic rules for TCP and UDP`() {
            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "test-cluster",
                    availabilityZones = listOf("us-west-2a"),
                    sshCidrs = listOf("1.2.3.4/32"),
                    sshPort = 22,
                )

            val vpcInternalTcp =
                config.securityGroupRules.find {
                    it.protocol == "tcp" &&
                        it.fromPort == Constants.Network.MIN_PORT &&
                        it.toPort == Constants.Network.MAX_PORT &&
                        it.cidr == Constants.Vpc.DEFAULT_CIDR
                }
            val vpcInternalUdp =
                config.securityGroupRules.find {
                    it.protocol == "udp" &&
                        it.fromPort == Constants.Network.MIN_PORT &&
                        it.toPort == Constants.Network.MAX_PORT &&
                        it.cidr == Constants.Vpc.DEFAULT_CIDR
                }

            assertThat(vpcInternalTcp).isNotNull
            assertThat(vpcInternalUdp).isNotNull
        }

        @Test
        fun `should use correct naming convention`() {
            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "my-cluster",
                    availabilityZones = listOf("us-west-2a"),
                    sshCidrs = listOf("0.0.0.0/0"),
                    sshPort = 22,
                )

            assertThat(config.vpcName).isEqualTo("easy-db-lab-my-cluster")
            assertThat(config.securityGroupName).isEqualTo("easy-db-lab-my-cluster-sg")
            assertThat(config.internetGatewayName).isEqualTo("easy-db-lab-my-cluster-igw")
            assertThat(config.subnets[0].name).isEqualTo("easy-db-lab-my-cluster-subnet-0")
        }

        @Test
        fun `should use correct CIDR blocks from Constants`() {
            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "test-cluster",
                    availabilityZones = listOf("us-west-2a", "us-west-2b"),
                    sshCidrs = listOf("0.0.0.0/0"),
                    sshPort = 22,
                )

            assertThat(config.vpcCidr).isEqualTo(Constants.Vpc.DEFAULT_CIDR)
            assertThat(config.subnets[0].cidr).isEqualTo(Constants.Vpc.subnetCidr(0))
            assertThat(config.subnets[1].cidr).isEqualTo(Constants.Vpc.subnetCidr(1))
        }

        @Test
        fun `should use custom SSH port`() {
            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "test-cluster",
                    availabilityZones = listOf("us-west-2a"),
                    sshCidrs = listOf("1.2.3.4/32"),
                    sshPort = 2222,
                )

            val sshRule = config.securityGroupRules.find { it.cidr == "1.2.3.4/32" }
            assertThat(sshRule?.fromPort).isEqualTo(2222)
            assertThat(sshRule?.toPort).isEqualTo(2222)
        }

        @Test
        fun `should include standard tags`() {
            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "test-cluster",
                    availabilityZones = listOf("us-west-2a"),
                    sshCidrs = listOf("0.0.0.0/0"),
                    sshPort = 22,
                )

            assertThat(config.tags).containsEntry(Constants.Vpc.TAG_KEY, Constants.Vpc.TAG_VALUE)
        }

        @Test
        fun `should use custom CIDR for subnets when provided`() {
            val customCidr = "172.16.0.0/16"
            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "test-cluster",
                    availabilityZones = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
                    sshCidrs = listOf("0.0.0.0/0"),
                    sshPort = 22,
                    vpcCidr = customCidr,
                )

            assertThat(config.vpcCidr).isEqualTo(customCidr)
            assertThat(config.subnets[0].cidr).isEqualTo("172.16.1.0/24")
            assertThat(config.subnets[1].cidr).isEqualTo("172.16.2.0/24")
            assertThat(config.subnets[2].cidr).isEqualTo("172.16.3.0/24")
        }

        @Test
        fun `should use custom CIDR for security group VPC internal rules`() {
            val customCidr = "192.168.0.0/16"
            val config =
                InfrastructureConfig.forCluster(
                    clusterName = "test-cluster",
                    availabilityZones = listOf("us-west-2a"),
                    sshCidrs = listOf("1.2.3.4/32"),
                    sshPort = 22,
                    vpcCidr = customCidr,
                )

            val vpcInternalTcp =
                config.securityGroupRules.find {
                    it.protocol == "tcp" &&
                        it.fromPort == Constants.Network.MIN_PORT &&
                        it.toPort == Constants.Network.MAX_PORT
                }
            val vpcInternalUdp =
                config.securityGroupRules.find {
                    it.protocol == "udp" &&
                        it.fromPort == Constants.Network.MIN_PORT &&
                        it.toPort == Constants.Network.MAX_PORT
                }

            assertThat(vpcInternalTcp?.cidr).isEqualTo(customCidr)
            assertThat(vpcInternalUdp?.cidr).isEqualTo(customCidr)
        }
    }

    @Nested
    inner class ForPacker {
        @Test
        fun `should create single subnet`() {
            val config = InfrastructureConfig.forPacker(sshPort = 22)

            assertThat(config.subnets).hasSize(1)
            assertThat(config.subnets[0].name).isEqualTo("easy-db-lab-packer-subnet")
        }

        @Test
        fun `should allow SSH from anywhere`() {
            val config = InfrastructureConfig.forPacker(sshPort = 22)

            val sshRule = config.securityGroupRules.find { it.fromPort == 22 }
            assertThat(sshRule?.cidr).isEqualTo("0.0.0.0/0")
        }

        @Test
        fun `should use correct packer VPC name`() {
            val config = InfrastructureConfig.forPacker(sshPort = 22)

            assertThat(config.vpcName).isEqualTo(Constants.Vpc.PACKER_VPC_NAME)
        }

        @Test
        fun `should use correct naming for packer resources`() {
            val config = InfrastructureConfig.forPacker(sshPort = 22)

            assertThat(config.securityGroupName).isEqualTo("easy-db-lab-packer-sg")
            assertThat(config.internetGatewayName).isEqualTo("easy-db-lab-packer-igw")
        }

        @Test
        fun `should use custom SSH port`() {
            val config = InfrastructureConfig.forPacker(sshPort = 2222)

            val sshRule = config.securityGroupRules.first()
            assertThat(sshRule.fromPort).isEqualTo(2222)
            assertThat(sshRule.toPort).isEqualTo(2222)
        }
    }

    @Nested
    inner class SecurityGroupRuleCompanion {
        @Test
        fun `singlePort should create rule with same from and to port`() {
            val rule = SecurityGroupRule.singlePort(443, "0.0.0.0/0")

            assertThat(rule.fromPort).isEqualTo(443)
            assertThat(rule.toPort).isEqualTo(443)
            assertThat(rule.cidr).isEqualTo("0.0.0.0/0")
            assertThat(rule.protocol).isEqualTo("tcp")
        }

        @Test
        fun `singlePort should support custom protocol`() {
            val rule = SecurityGroupRule.singlePort(53, "10.0.0.0/16", protocol = "udp")

            assertThat(rule.fromPort).isEqualTo(53)
            assertThat(rule.toPort).isEqualTo(53)
            assertThat(rule.protocol).isEqualTo("udp")
        }
    }

    @Nested
    inner class SubnetConfig {
        @Test
        fun `should have optional availability zone`() {
            val withAz = SubnetConfig(name = "subnet-1", cidr = "10.0.1.0/24", availabilityZone = "us-west-2a")
            val withoutAz = SubnetConfig(name = "subnet-2", cidr = "10.0.2.0/24")

            assertThat(withAz.availabilityZone).isEqualTo("us-west-2a")
            assertThat(withoutAz.availabilityZone).isNull()
        }
    }

    @Nested
    inner class VpcNetworkingConfigTests {
        @Test
        fun `should have default empty tags`() {
            val config =
                VpcNetworkingConfig(
                    vpcId = "vpc-123",
                    clusterName = "test",
                    clusterId = "cluster-123",
                    region = "us-west-2",
                    availabilityZones = listOf("a"),
                    isOpen = false,
                )

            assertThat(config.tags).isEmpty()
        }

        @Test
        fun `should support custom tags`() {
            val config =
                VpcNetworkingConfig(
                    vpcId = "vpc-123",
                    clusterName = "test",
                    clusterId = "cluster-123",
                    region = "us-west-2",
                    availabilityZones = listOf("a"),
                    isOpen = false,
                    tags = mapOf("Environment" to "test"),
                )

            assertThat(config.tags).containsEntry("Environment", "test")
        }

        @Test
        fun `should have default CIDR when not specified`() {
            val config =
                VpcNetworkingConfig(
                    vpcId = "vpc-123",
                    clusterName = "test",
                    clusterId = "cluster-123",
                    region = "us-west-2",
                    availabilityZones = listOf("a"),
                    isOpen = false,
                )

            assertThat(config.vpcCidr).isEqualTo(Constants.Vpc.DEFAULT_CIDR)
        }

        @Test
        fun `should support custom CIDR`() {
            val customCidr = "172.16.0.0/16"
            val config =
                VpcNetworkingConfig(
                    vpcId = "vpc-123",
                    clusterName = "test",
                    clusterId = "cluster-123",
                    region = "us-west-2",
                    availabilityZones = listOf("a"),
                    isOpen = false,
                    vpcCidr = customCidr,
                )

            assertThat(config.vpcCidr).isEqualTo(customCidr)
        }
    }
}
