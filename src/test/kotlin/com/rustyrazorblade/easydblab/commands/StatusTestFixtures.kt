package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo

/**
 * The fixed AWS cluster [StatusTest] reports on: two db nodes, one app node and a control node, the
 * VPC they run in, the EC2 view of those instances and the cluster's security group. Kept apart
 * from the test so the test class holds only the behaviour under test.
 */
object StatusTestFixtures {
    val testHosts =
        mapOf(
            ServerType.Cassandra to
                listOf(
                    ClusterHost(
                        publicIp = "54.1.2.3",
                        privateIp = "10.0.1.100",
                        alias = "db0",
                        availabilityZone = "us-west-2a",
                        instanceId = "i-db0",
                    ),
                    ClusterHost(
                        publicIp = "54.1.2.4",
                        privateIp = "10.0.1.101",
                        alias = "db1",
                        availabilityZone = "us-west-2b",
                        instanceId = "i-db1",
                    ),
                ),
            ServerType.Stress to
                listOf(
                    ClusterHost(
                        publicIp = "54.2.3.4",
                        privateIp = "10.0.2.100",
                        alias = "app0",
                        availabilityZone = "us-west-2a",
                        instanceId = "i-app0",
                    ),
                ),
            ServerType.Control to
                listOf(
                    ClusterHost(
                        publicIp = "54.3.4.5",
                        privateIp = "10.0.3.100",
                        alias = "control0",
                        availabilityZone = "us-west-2a",
                        instanceId = "i-control0",
                    ),
                ),
        )

    val testInfrastructure =
        InfrastructureState(
            vpcId = "vpc-12345",
            region = "us-west-2",
            internetGatewayId = "igw-12345",
            subnetIds = listOf("subnet-a", "subnet-b", "subnet-c"),
            routeTableId = "rtb-12345",
            securityGroupId = "sg-12345",
        )

    val instanceDetails =
        listOf(
            InstanceDetails(
                instanceId = "i-db0",
                state = "running",
                publicIp = "54.1.2.3",
                privateIp = "10.0.1.100",
                availabilityZone = "us-west-2a",
                instanceType = "r3.2xlarge",
            ),
            InstanceDetails(
                instanceId = "i-db1",
                state = "running",
                publicIp = "54.1.2.4",
                privateIp = "10.0.1.101",
                availabilityZone = "us-west-2b",
                instanceType = "r3.2xlarge",
            ),
            InstanceDetails(
                instanceId = "i-app0",
                state = "running",
                publicIp = "54.2.3.4",
                privateIp = "10.0.2.100",
                availabilityZone = "us-west-2a",
                instanceType = "m5.xlarge",
            ),
            InstanceDetails(
                instanceId = "i-control0",
                state = "running",
                publicIp = "54.3.4.5",
                privateIp = "10.0.3.100",
                availabilityZone = "us-west-2a",
                instanceType = "t3.medium",
            ),
        )

    val securityGroupDetails =
        SecurityGroupDetails(
            securityGroupId = "sg-12345",
            name = "easy-db-lab-sg",
            description = "easy-db-lab security group",
            vpcId = "vpc-12345",
            inboundRules =
                listOf(
                    SecurityGroupRuleInfo(
                        protocol = "tcp",
                        fromPort = 22,
                        toPort = 22,
                        cidrBlocks = listOf("0.0.0.0/0"),
                        description = "SSH access",
                    ),
                ),
            outboundRules = emptyList(),
        )
}
