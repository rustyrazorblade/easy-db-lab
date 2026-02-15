package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class ClusterStateTest {
    @Test
    fun `ClusterState should save and load InitConfig correctly`(
        @TempDir tempDir: File,
    ) {
        // Set the file path to use temp directory
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create InitConfig with test data
        val initConfig =
            InitConfig(
                cassandraInstances = 3,
                stressInstances = 1,
                instanceType = "m5d.xlarge",
                stressInstanceType = "c5.2xlarge",
                azs = listOf("a", "b", "c"),
                ami = "ami-12345",
                region = "us-west-2",
                name = "test-cluster",
                ebsType = "gp3",
                ebsSize = 256,
                ebsIops = 3000,
                ebsThroughput = 125,
                ebsOptimized = true,
                open = false,
                controlInstances = 1,
                controlInstanceType = "t3.xlarge",
                tags = mapOf("env" to "test", "team" to "platform"),
            )

        // Create and save ClusterState
        val originalState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf("cassandra" to "4.1.3"),
                initConfig = initConfig,
            )
        manager.save(originalState)

        // Verify file was created
        assertThat(stateFile).exists()

        // Load the state back
        val loadedState = manager.load()

        // Verify all fields match
        assertThat(loadedState.name).isEqualTo("test-cluster")
        assertThat(loadedState.versions).containsEntry("cassandra", "4.1.3")

        // Verify InitConfig was preserved
        assertThat(loadedState.initConfig).isNotNull
        val loadedConfig = loadedState.initConfig!!

        assertThat(loadedConfig.cassandraInstances).isEqualTo(3)
        assertThat(loadedConfig.stressInstances).isEqualTo(1)
        assertThat(loadedConfig.instanceType).isEqualTo("m5d.xlarge")
        assertThat(loadedConfig.stressInstanceType).isEqualTo("c5.2xlarge")
        assertThat(loadedConfig.azs).containsExactly("a", "b", "c")
        assertThat(loadedConfig.ami).isEqualTo("ami-12345")
        assertThat(loadedConfig.region).isEqualTo("us-west-2")
        assertThat(loadedConfig.name).isEqualTo("test-cluster")
        assertThat(loadedConfig.ebsType).isEqualTo("gp3")
        assertThat(loadedConfig.ebsSize).isEqualTo(256)
        assertThat(loadedConfig.ebsIops).isEqualTo(3000)
        assertThat(loadedConfig.ebsThroughput).isEqualTo(125)
        assertThat(loadedConfig.ebsOptimized).isTrue()
        assertThat(loadedConfig.open).isFalse()
        assertThat(loadedConfig.controlInstances).isEqualTo(1)
        assertThat(loadedConfig.controlInstanceType).isEqualTo("t3.xlarge")
        assertThat(loadedConfig.tags).containsEntry("env", "test")
        assertThat(loadedConfig.tags).containsEntry("team", "platform")
    }

    @Test
    fun `ClusterState should handle null InitConfig gracefully`(
        @TempDir tempDir: File,
    ) {
        // Set the file path to use temp directory
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create ClusterState without InitConfig (backward compatibility)
        val state =
            ClusterState(
                name = "legacy-cluster",
                versions = mutableMapOf(),
                initConfig = null,
            )
        manager.save(state)

        // Load it back
        val loadedState = manager.load()

        // Verify it loads correctly with null InitConfig
        assertThat(loadedState.name).isEqualTo("legacy-cluster")
        assertThat(loadedState.initConfig).isNull()
    }

    @Test
    fun `InitConfig region should be used for datacenter determination`() {
        // Create InitConfig with specific region
        val initConfig =
            InitConfig(
                cassandraInstances = 3,
                stressInstances = 0,
                instanceType = "m5d.xlarge",
                stressInstanceType = "c5.2xlarge",
                azs = listOf(),
                ami = "",
                region = "eu-west-1",
                name = "eu-cluster",
                ebsType = "NONE",
                ebsSize = 0,
                ebsIops = 0,
                ebsThroughput = 0,
                ebsOptimized = false,
                open = false,
                controlInstances = 1,
                controlInstanceType = "t3.xlarge",
                tags = mapOf(),
            )

        // The region should be used as the datacenter name for EC2Snitch
        assertThat(initConfig.region).isEqualTo("eu-west-1")

        // This is what will be used for CASSANDRA_DATACENTER
        val datacenter = initConfig.region
        assertThat(datacenter).isEqualTo("eu-west-1")
    }

    @Test
    fun `ClusterState should load legacy state file without InitConfig`(
        @TempDir tempDir: File,
    ) {
        // Set the file path to use temp directory
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create a legacy JSON without initConfig field
        val legacyJson =
            """
            {
              "name": "legacy-cluster",
              "default": {
                "version": "3.11.10",
                "javaVersion": "8"
              },
              "nodes": {},
              "versions": {
                "cassandra": "3.11.10"
              }
            }
            """.trimIndent()

        stateFile.writeText(legacyJson)

        // Should load without throwing an exception
        val loadedState = manager.load()

        assertThat(loadedState.name).isEqualTo("legacy-cluster")
        assertThat(loadedState.default.version).isEqualTo("3.11.10")
        assertThat(loadedState.initConfig).isNull()
    }

    @Test
    fun `ClusterState should load state with partial InitConfig`(
        @TempDir tempDir: File,
    ) {
        // Set the file path to use temp directory
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create JSON with partial InitConfig (missing some fields)
        val partialJson =
            """
            {
              "name": "partial-cluster",
              "default": {},
              "nodes": {},
              "versions": {},
              "initConfig": {
                "region": "ap-southeast-1",
                "name": "partial-cluster",
                "cassandraInstances": 5
              }
            }
            """.trimIndent()

        stateFile.writeText(partialJson)

        // Should load and fill in missing fields with defaults
        val loadedState = manager.load()

        assertThat(loadedState.name).isEqualTo("partial-cluster")
        assertThat(loadedState.initConfig).isNotNull

        val config = loadedState.initConfig!!
        // Provided values should be preserved
        assertThat(config.region).isEqualTo("ap-southeast-1")
        assertThat(config.name).isEqualTo("partial-cluster")
        assertThat(config.cassandraInstances).isEqualTo(5)

        // Missing values should use defaults
        assertThat(config.stressInstances).isEqualTo(0)
        assertThat(config.instanceType).isEqualTo("r3.2xlarge")
        assertThat(config.controlInstances).isEqualTo(1)
        assertThat(config.controlInstanceType).isEqualTo("t3.xlarge")
    }

    @Test
    fun `ClusterState should handle state file with extra unknown fields`(
        @TempDir tempDir: File,
    ) {
        // Set the file path to use temp directory
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create JSON with extra fields that don't exist in current schema
        val jsonWithExtraFields =
            """
            {
              "name": "future-cluster",
              "default": {},
              "nodes": {},
              "versions": {},
              "futureField": "some value",
              "anotherNewField": 123,
              "initConfig": {
                "region": "us-east-1",
                "name": "future-cluster",
                "cassandraInstances": 3,
                "someNewConfigField": "ignored"
              }
            }
            """.trimIndent()

        stateFile.writeText(jsonWithExtraFields)

        // Should load without errors, ignoring unknown fields
        val loadedState = manager.load()

        assertThat(loadedState.name).isEqualTo("future-cluster")
        assertThat(loadedState.initConfig).isNotNull
        assertThat(loadedState.initConfig!!.region).isEqualTo("us-east-1")
    }

    @Test
    fun `ClusterState should save and load hosts correctly`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create test hosts
        val cassandraHosts =
            listOf(
                ClusterHost(
                    publicIp = "54.1.2.3",
                    privateIp = "10.0.1.10",
                    alias = "db0",
                    availabilityZone = "us-west-2a",
                ),
                ClusterHost(
                    publicIp = "54.1.2.4",
                    privateIp = "10.0.1.11",
                    alias = "db1",
                    availabilityZone = "us-west-2b",
                ),
            )

        val controlHosts =
            listOf(
                ClusterHost(
                    publicIp = "54.1.2.5",
                    privateIp = "10.0.1.20",
                    alias = "control0",
                    availabilityZone = "us-west-2a",
                ),
            )

        val hosts =
            mapOf(
                ServerType.Cassandra to cassandraHosts,
                ServerType.Control to controlHosts,
            )

        // Create and save ClusterState with hosts
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = hosts,
            )
        manager.save(state)

        // Load it back
        val loadedState = manager.load()

        // Verify hosts were preserved
        assertThat(loadedState.hosts).hasSize(2)
        assertThat(loadedState.hosts[ServerType.Cassandra]).hasSize(2)
        assertThat(loadedState.hosts[ServerType.Control]).hasSize(1)

        val loadedCassandra = loadedState.hosts[ServerType.Cassandra]!!
        assertThat(loadedCassandra[0].publicIp).isEqualTo("54.1.2.3")
        assertThat(loadedCassandra[0].privateIp).isEqualTo("10.0.1.10")
        assertThat(loadedCassandra[0].alias).isEqualTo("db0")
        assertThat(loadedCassandra[0].availabilityZone).isEqualTo("us-west-2a")

        val loadedControl = loadedState.hosts[ServerType.Control]!!
        assertThat(loadedControl[0].publicIp).isEqualTo("54.1.2.5")
        assertThat(loadedControl[0].alias).isEqualTo("control0")
    }

    @Test
    fun `ClusterState should track infrastructure status`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )

        // Initially UNKNOWN
        assertThat(state.infrastructureStatus).isEqualTo(InfrastructureStatus.UNKNOWN)
        assertThat(state.isInfrastructureUp()).isFalse()

        // Mark as UP
        state.markInfrastructureUp()
        manager.save(state)
        assertThat(state.infrastructureStatus).isEqualTo(InfrastructureStatus.UP)
        assertThat(state.isInfrastructureUp()).isTrue()

        // Load from file and verify status persisted
        val loadedState = manager.load()
        assertThat(loadedState.infrastructureStatus).isEqualTo(InfrastructureStatus.UP)
        assertThat(loadedState.isInfrastructureUp()).isTrue()

        // Mark as DOWN
        loadedState.markInfrastructureDown()
        manager.save(loadedState)
        assertThat(loadedState.infrastructureStatus).isEqualTo(InfrastructureStatus.DOWN)
        assertThat(loadedState.isInfrastructureUp()).isFalse()

        // Verify DOWN status persisted
        val finalState = manager.load()
        assertThat(finalState.infrastructureStatus).isEqualTo(InfrastructureStatus.DOWN)
        assertThat(finalState.isInfrastructureUp()).isFalse()
    }

    @Test
    fun `updateHosts should update hosts and timestamp`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )
        manager.save(state)

        val originalTimestamp = state.lastAccessedAt

        // Wait a moment to ensure timestamp changes
        Thread.sleep(10)

        val hosts =
            mapOf(
                ServerType.Cassandra to
                    listOf(
                        ClusterHost(
                            publicIp = "54.1.2.3",
                            privateIp = "10.0.1.10",
                            alias = "db0",
                            availabilityZone = "us-west-2a",
                        ),
                    ),
            )

        state.updateHosts(hosts)
        manager.save(state)

        // Verify timestamp was updated
        assertThat(state.lastAccessedAt).isAfter(originalTimestamp)

        // Verify hosts were saved
        val loadedState = manager.load()
        assertThat(loadedState.hosts).hasSize(1)
        assertThat(loadedState.hosts[ServerType.Cassandra]).hasSize(1)
    }

    @Test
    fun `getControlHost should return first control host`() {
        // Test with no control hosts
        val stateNoControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )
        assertThat(stateNoControl.getControlHost()).isNull()

        // Test with control hosts
        val controlHost =
            ClusterHost(
                publicIp = "54.1.2.5",
                privateIp = "10.0.1.20",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )

        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mapOf(
                        ServerType.Control to listOf(controlHost),
                    ),
            )

        val retrieved = stateWithControl.getControlHost()
        assertThat(retrieved).isNotNull
        assertThat(retrieved!!.publicIp).isEqualTo("54.1.2.5")
        assertThat(retrieved.alias).isEqualTo("control0")
    }

    @Test
    fun `validateHostsMatch should detect matching hosts`() {
        val hosts1 =
            mapOf(
                ServerType.Cassandra to
                    listOf(
                        ClusterHost("54.1.2.3", "10.0.1.10", "db0", "us-west-2a"),
                        ClusterHost("54.1.2.4", "10.0.1.11", "db1", "us-west-2b"),
                    ),
                ServerType.Control to
                    listOf(
                        ClusterHost("54.1.2.5", "10.0.1.20", "control0", "us-west-2a"),
                    ),
            )

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = hosts1,
            )

        // Same hosts should match
        assertThat(state.validateHostsMatch(hosts1)).isTrue()

        // Hosts with different private IPs but same public IPs and aliases should match
        val hosts2 =
            mapOf(
                ServerType.Cassandra to
                    listOf(
                        ClusterHost("54.1.2.3", "10.0.2.10", "db0", "us-west-2a"),
                        ClusterHost("54.1.2.4", "10.0.2.11", "db1", "us-west-2b"),
                    ),
                ServerType.Control to
                    listOf(
                        ClusterHost("54.1.2.5", "10.0.2.20", "control0", "us-west-2a"),
                    ),
            )
        assertThat(state.validateHostsMatch(hosts2)).isTrue()
    }

    @Test
    fun `validateHostsMatch should detect mismatched hosts`() {
        val hosts1 =
            mapOf(
                ServerType.Cassandra to
                    listOf(
                        ClusterHost("54.1.2.3", "10.0.1.10", "db0", "us-west-2a"),
                    ),
            )

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = hosts1,
            )

        // Different public IP should not match
        val hostsDifferentIP =
            mapOf(
                ServerType.Cassandra to
                    listOf(
                        ClusterHost("54.9.9.9", "10.0.1.10", "db0", "us-west-2a"),
                    ),
            )
        assertThat(state.validateHostsMatch(hostsDifferentIP)).isFalse()

        // Different alias should not match
        val hostsDifferentAlias =
            mapOf(
                ServerType.Cassandra to
                    listOf(
                        ClusterHost("54.1.2.3", "10.0.1.10", "cassandra99", "us-west-2a"),
                    ),
            )
        assertThat(state.validateHostsMatch(hostsDifferentAlias)).isFalse()

        // Different server types should not match
        val hostsDifferentType =
            mapOf(
                ServerType.Stress to
                    listOf(
                        ClusterHost("54.1.2.3", "10.0.1.10", "db0", "us-west-2a"),
                    ),
            )
        assertThat(state.validateHostsMatch(hostsDifferentType)).isFalse()

        // Different count should not match
        val hostsMoreHosts =
            mapOf(
                ServerType.Cassandra to
                    listOf(
                        ClusterHost("54.1.2.3", "10.0.1.10", "db0", "us-west-2a"),
                        ClusterHost("54.1.2.4", "10.0.1.11", "db1", "us-west-2b"),
                    ),
            )
        assertThat(state.validateHostsMatch(hostsMoreHosts)).isFalse()
    }

    @Test
    fun `ClusterState should have unique clusterId`() {
        val state1 =
            ClusterState(
                name = "cluster1",
                versions = mutableMapOf(),
            )

        val state2 =
            ClusterState(
                name = "cluster2",
                versions = mutableMapOf(),
            )

        // Each cluster should have a unique ID
        assertThat(state1.clusterId).isNotEqualTo(state2.clusterId)
    }

    @Test
    fun `ClusterState should preserve clusterId on save and load`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val originalState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )
        val originalId = originalState.clusterId
        manager.save(originalState)

        val loadedState = manager.load()
        assertThat(loadedState.clusterId).isEqualTo(originalId)
    }

    @Test
    fun `ClusterState should handle timestamp fields correctly`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val beforeCreate = Instant.now()
        Thread.sleep(10)

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )

        Thread.sleep(10)
        val afterCreate = Instant.now()

        // createdAt and lastAccessedAt should be set
        assertThat(state.createdAt).isBetween(beforeCreate, afterCreate)
        assertThat(state.lastAccessedAt).isBetween(beforeCreate, afterCreate)

        manager.save(state)
        val loadedState = manager.load()

        // Timestamps should be preserved
        assertThat(loadedState.createdAt).isEqualTo(state.createdAt)
        assertThat(loadedState.lastAccessedAt).isEqualTo(state.lastAccessedAt)
    }

    @Test
    fun `ClusterState should load legacy state without new fields`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create legacy JSON without new fields
        val legacyJson =
            """
            {
              "name": "legacy-cluster",
              "default": {},
              "nodes": {},
              "versions": {}
            }
            """.trimIndent()

        stateFile.writeText(legacyJson)

        // Should load without errors and use defaults
        val loadedState = manager.load()

        assertThat(loadedState.name).isEqualTo("legacy-cluster")
        assertThat(loadedState.infrastructureStatus).isEqualTo(InfrastructureStatus.UNKNOWN)
        assertThat(loadedState.hosts).isEmpty()
        assertThat(loadedState.clusterId).isNotEmpty()
    }

    @Test
    fun `s3Path extension function should create ClusterS3Path`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-abc12345",
                clusterId = "test-id",
            )

        val s3Path = state.s3Path()

        assertThat(s3Path.bucket).isEqualTo("easy-db-lab-test-abc12345")
        assertThat(s3Path.toString()).isEqualTo("s3://easy-db-lab-test-abc12345/clusters/test-cluster-test-id")
    }

    @Test
    fun `s3Path extension function should throw when s3Bucket not configured`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = null,
            )

        assertThatThrownBy { state.s3Path() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("S3 bucket not configured")
    }
}
