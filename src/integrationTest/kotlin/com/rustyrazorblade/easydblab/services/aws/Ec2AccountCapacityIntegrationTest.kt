package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateNetworkInterfaceRequest
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest
import software.amazon.awssdk.services.ec2.model.Filter

/**
 * The EC2 half of [Ec2AccountCapacity] against LocalStack's EC2: instance type facts, zone
 * offerings, network interface counts, free subnet addresses per zone, and the RunInstances dry
 * run, permitted and refused. LocalStack does not serve Service Quotas, so the two quota reads are
 * not exercised here (their client is a mock no test calls); they, the Spot and family filters of
 * the vCPUs in use, and the dry run's throttle retry are tested in `Ec2AccountCapacityTest`.
 *
 * The EC2 backend is shared with the other integration tests, so the VPC and its subnets are this
 * class's own, and the network interface count is compared before and after its own interfaces.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Ec2AccountCapacityIntegrationTest {
    private lateinit var ec2: Ec2Client
    private lateinit var capacity: Ec2AccountCapacity
    private val region get() = SharedLocalStack.region()

    @BeforeAll
    fun setup() {
        ec2 = SharedLocalStack.ec2Client()
        capacity = Ec2AccountCapacity(ec2, mock())
    }

    private fun vpcWithSubnets(vararg subnets: Pair<String, String>): Pair<String, List<String>> {
        val vpcId = ec2.createVpc(CreateVpcRequest.builder().cidrBlock("10.77.0.0/16").build()).vpc().vpcId()
        val subnetIds =
            subnets.map { (zone, cidr) ->
                ec2
                    .createSubnet(
                        CreateSubnetRequest
                            .builder()
                            .vpcId(vpcId)
                            .cidrBlock(cidr)
                            .availabilityZone(region + zone)
                            .build(),
                    ).subnet()
                    .subnetId()
            }
        return vpcId to subnetIds
    }

    @Test
    fun `instance types report vCPUs and network limits, and an unknown type is missing`() {
        val facts = capacity.instanceTypes(listOf("m5.xlarge", "zz9.huge"))

        assertThat(facts).containsOnlyKeys("m5.xlarge")
        assertThat(facts.getValue("m5.xlarge").vcpus).isEqualTo(4)
        assertThat(facts.getValue("m5.xlarge").maxNetworkInterfaces).isGreaterThan(1)
        assertThat(facts.getValue("m5.xlarge").ipv4AddressesPerInterface).isGreaterThan(1)
    }

    @Test
    fun `zone offerings are limited to the zones asked about`() {
        val zones = capacity.zonesOffering("m5.xlarge", listOf(region + "a", region + "b"))

        assertThat(zones).isNotEmpty.isSubsetOf(region + "a", region + "b")
    }

    @Test
    fun `free addresses are summed per zone of the VPC's own subnets`() {
        val (vpcId, _) =
            vpcWithSubnets(
                "a" to "10.77.0.0/24",
                "a" to "10.77.1.0/24",
                "b" to "10.77.2.0/28",
            )

        val free = capacity.freeAddressesByZone(vpcId)

        assertThat(free).containsOnlyKeys(region + "a", region + "b")
        assertThat(free.getValue(region + "a")).isEqualTo(2 * (256 - 5))
        assertThat(free.getValue(region + "b")).isEqualTo(16 - 5)
    }

    @Test
    fun `network interfaces in use counts every interface in the region`() {
        val (_, subnetIds) = vpcWithSubnets("a" to "10.77.8.0/24")
        val before = capacity.networkInterfacesInUse()

        repeat(2) {
            ec2.createNetworkInterface(CreateNetworkInterfaceRequest.builder().subnetId(subnetIds.first()).build())
        }

        assertThat(capacity.networkInterfacesInUse()).isEqualTo(before + 2)
    }

    @Test
    fun `a dry run of a valid launch is permitted and launches nothing`() {
        val (_, subnetIds) = vpcWithSubnets("a" to "10.77.16.0/24")
        val ami =
            ec2
                .describeImages(
                    DescribeImagesRequest
                        .builder()
                        .filters(
                            Filter
                                .builder()
                                .name("architecture")
                                .values("x86_64")
                                .build(),
                        ).build(),
                ).images()
                .first()
                .imageId()
        val config =
            InstanceCreationConfig(
                serverType = ServerType.Cassandra,
                indexes = listOf(0, 1, 2),
                instanceType = "m5.xlarge",
                amiId = ami,
                keyName = "dry-run-key",
                securityGroupId = "sg-dryrun",
                subnetIds = subnetIds,
                iamInstanceProfile = "dry-run-profile",
                ebsConfig = null,
                tags = mapOf("ClusterId" to "dry-run-capacity"),
                clusterName = "dry-run",
            )

        assertThat(capacity.dryRunLaunch(config)).isEqualTo(LaunchDryRun.Permitted)
        val launched =
            ec2
                .describeInstances {
                    it.filters(
                        Filter
                            .builder()
                            .name("tag:ClusterId")
                            .values("dry-run-capacity")
                            .build(),
                    )
                }.reservations()
        assertThat(launched).isEmpty()
    }

    /**
     * A launch AWS would refuse is reported with AWS's own code and message. A subnet that does
     * not exist is refused by LocalStack as it is by EC2. The service-quota and Spot paths are
     * covered at the SDK boundary in `Ec2AccountCapacityTest`.
     */
    @Test
    fun `a dry run into a subnet that does not exist is refused with AWS's code and message`() {
        val config =
            InstanceCreationConfig(
                serverType = ServerType.Cassandra,
                indexes = listOf(0),
                instanceType = "m5.xlarge",
                amiId = "ami-00000000000000000",
                keyName = "dry-run-key",
                securityGroupId = "sg-dryrun",
                subnetIds = listOf("subnet-00000000000000000"),
                iamInstanceProfile = "dry-run-profile",
                ebsConfig = null,
                tags = mapOf("ClusterId" to "dry-run-refused"),
                clusterName = "dry-run",
            )

        val answer = capacity.dryRunLaunch(config)

        assertThat(answer).isInstanceOf(LaunchDryRun.Refused::class.java)
        val refused = answer as LaunchDryRun.Refused
        assertThat(refused.errorCode).isNotBlank().isNotEqualTo("DryRunOperation")
        assertThat(refused.message).isNotBlank()
    }
}
