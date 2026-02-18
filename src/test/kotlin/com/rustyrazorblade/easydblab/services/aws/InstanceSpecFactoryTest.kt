package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for InstanceSpecFactory.
 */
class InstanceSpecFactoryTest {
    private lateinit var factory: InstanceSpecFactory

    @BeforeEach
    fun setup() {
        factory = DefaultInstanceSpecFactory()
    }

    @Nested
    inner class CreateInstanceSpecs {
        @Test
        fun `should create specs for all server types`() {
            val initConfig =
                createInitConfig(
                    cassandraInstances = 3,
                    stressInstances = 2,
                    controlInstances = 1,
                )

            val specs = factory.createInstanceSpecs(initConfig, emptyMap())

            assertThat(specs).hasSize(3)
            assertThat(specs.map { it.serverType }).containsExactly(
                ServerType.Cassandra,
                ServerType.Stress,
                ServerType.Control,
            )
        }

        @Test
        fun `should calculate needed count correctly when no existing instances`() {
            val initConfig =
                createInitConfig(
                    cassandraInstances = 3,
                    stressInstances = 2,
                    controlInstances = 1,
                )

            val specs = factory.createInstanceSpecs(initConfig, emptyMap())

            val cassandraSpec = specs.find { it.serverType == ServerType.Cassandra }!!
            val stressSpec = specs.find { it.serverType == ServerType.Stress }!!
            val controlSpec = specs.find { it.serverType == ServerType.Control }!!

            assertThat(cassandraSpec.neededCount).isEqualTo(3)
            assertThat(stressSpec.neededCount).isEqualTo(2)
            assertThat(controlSpec.neededCount).isEqualTo(1)
        }

        @Test
        fun `should subtract existing instances from needed count`() {
            val initConfig =
                createInitConfig(
                    cassandraInstances = 3,
                    stressInstances = 2,
                    controlInstances = 1,
                )

            val existingInstances =
                mapOf(
                    ServerType.Cassandra to
                        listOf(
                            createDiscoveredInstance("i-1", ServerType.Cassandra),
                            createDiscoveredInstance("i-2", ServerType.Cassandra),
                        ),
                    ServerType.Stress to
                        listOf(
                            createDiscoveredInstance("i-3", ServerType.Stress),
                        ),
                )

            val specs = factory.createInstanceSpecs(initConfig, existingInstances)

            val cassandraSpec = specs.find { it.serverType == ServerType.Cassandra }!!
            val stressSpec = specs.find { it.serverType == ServerType.Stress }!!
            val controlSpec = specs.find { it.serverType == ServerType.Control }!!

            assertThat(cassandraSpec.neededCount).isEqualTo(1) // 3 - 2
            assertThat(stressSpec.neededCount).isEqualTo(1) // 2 - 1
            assertThat(controlSpec.neededCount).isEqualTo(1) // 1 - 0
        }

        @Test
        fun `should return zero or negative when sufficient instances exist`() {
            val initConfig =
                createInitConfig(
                    cassandraInstances = 2,
                    stressInstances = 1,
                    controlInstances = 1,
                )

            val existingInstances =
                mapOf(
                    ServerType.Cassandra to
                        listOf(
                            createDiscoveredInstance("i-1", ServerType.Cassandra),
                            createDiscoveredInstance("i-2", ServerType.Cassandra),
                            createDiscoveredInstance("i-3", ServerType.Cassandra),
                        ),
                    ServerType.Stress to
                        listOf(
                            createDiscoveredInstance("i-4", ServerType.Stress),
                        ),
                )

            val specs = factory.createInstanceSpecs(initConfig, existingInstances)

            val cassandraSpec = specs.find { it.serverType == ServerType.Cassandra }!!
            val stressSpec = specs.find { it.serverType == ServerType.Stress }!!

            assertThat(cassandraSpec.neededCount).isEqualTo(-1) // 2 - 3
            assertThat(stressSpec.neededCount).isEqualTo(0) // 1 - 1
        }

        @Test
        fun `should use correct instance types from config`() {
            val initConfig =
                createInitConfig(
                    instanceType = "m5.xlarge",
                    stressInstanceType = "c5.large",
                    controlInstanceType = "t3.medium",
                )

            val specs = factory.createInstanceSpecs(initConfig, emptyMap())

            val cassandraSpec = specs.find { it.serverType == ServerType.Cassandra }!!
            val stressSpec = specs.find { it.serverType == ServerType.Stress }!!
            val controlSpec = specs.find { it.serverType == ServerType.Control }!!

            assertThat(cassandraSpec.instanceType).isEqualTo("m5.xlarge")
            assertThat(stressSpec.instanceType).isEqualTo("c5.large")
            assertThat(controlSpec.instanceType).isEqualTo("t3.medium")
        }

        @Test
        fun `should only attach EBS config to Cassandra nodes`() {
            val initConfig =
                createInitConfig(
                    ebsType = "gp3",
                    ebsSize = 100,
                )

            val specs = factory.createInstanceSpecs(initConfig, emptyMap())

            val cassandraSpec = specs.find { it.serverType == ServerType.Cassandra }!!
            val stressSpec = specs.find { it.serverType == ServerType.Stress }!!
            val controlSpec = specs.find { it.serverType == ServerType.Control }!!

            assertThat(cassandraSpec.ebsConfig).isNotNull
            assertThat(stressSpec.ebsConfig).isNull()
            assertThat(controlSpec.ebsConfig).isNull()
        }
    }

    @Nested
    inner class CreateEbsConfig {
        @Test
        fun `should return null for NONE type`() {
            val initConfig = createInitConfig(ebsType = "NONE")

            val result = factory.createEbsConfig(initConfig)

            assertThat(result).isNull()
        }

        @Test
        fun `should create config for gp3 with all parameters`() {
            val initConfig =
                createInitConfig(
                    ebsType = "GP3",
                    ebsSize = 200,
                    ebsIops = 4000,
                    ebsThroughput = 250,
                )

            val result = factory.createEbsConfig(initConfig)

            assertThat(result).isNotNull
            assertThat(result!!.volumeType).isEqualTo("gp3")
            assertThat(result.volumeSize).isEqualTo(200)
            assertThat(result.iops).isEqualTo(4000)
            assertThat(result.throughput).isEqualTo(250)
        }

        @Test
        fun `should lowercase volume type`() {
            val initConfig = createInitConfig(ebsType = "GP3")

            val result = factory.createEbsConfig(initConfig)

            assertThat(result!!.volumeType).isEqualTo("gp3")
        }

        @Test
        fun `should set iops to null when zero`() {
            val initConfig =
                createInitConfig(
                    ebsType = "gp2",
                    ebsIops = 0,
                )

            val result = factory.createEbsConfig(initConfig)

            assertThat(result!!.iops).isNull()
        }

        @Test
        fun `should set throughput to null when zero`() {
            val initConfig =
                createInitConfig(
                    ebsType = "gp3",
                    ebsThroughput = 0,
                )

            val result = factory.createEbsConfig(initConfig)

            assertThat(result!!.throughput).isNull()
        }
    }

    /**
     * Helper to create InitConfig with default values.
     */
    private fun createInitConfig(
        cassandraInstances: Int = 3,
        stressInstances: Int = 1,
        controlInstances: Int = 1,
        instanceType: String = "m5.large",
        stressInstanceType: String = "m5.large",
        controlInstanceType: String = "m5.large",
        ebsType: String = "NONE",
        ebsSize: Int = 100,
        ebsIops: Int = 0,
        ebsThroughput: Int = 0,
    ): InitConfig =
        InitConfig(
            cassandraInstances = cassandraInstances,
            stressInstances = stressInstances,
            instanceType = instanceType,
            stressInstanceType = stressInstanceType,
            region = "us-west-2",
            name = "test-cluster",
            ebsType = ebsType,
            ebsSize = ebsSize,
            ebsIops = ebsIops,
            ebsThroughput = ebsThroughput,
            controlInstances = controlInstances,
            controlInstanceType = controlInstanceType,
        )

    /**
     * Helper to create a DiscoveredInstance for testing.
     */
    private fun createDiscoveredInstance(
        instanceId: String,
        serverType: ServerType,
    ): DiscoveredInstance =
        DiscoveredInstance(
            instanceId = instanceId,
            publicIp = "1.2.3.4",
            privateIp = "10.0.0.1",
            alias = "${serverType.name.lowercase()}0",
            availabilityZone = "us-west-2a",
            serverType = serverType,
            state = "running",
        )
}
