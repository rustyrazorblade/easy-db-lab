package com.rustyrazorblade.easydblab.commands.cassandra

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class WriteConfigTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    private val testCassandraHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.1",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(testCassandraHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
    }

    @Test
    fun `execute writes config file with cluster name`() {
        val command = WriteConfig()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Writing new configuration file")

        // Verify the file was written
        val configFile = File(context.workingDirectory, "cassandra.patch.yaml")
        assertThat(configFile).exists()
        val content = configFile.readText()
        assertThat(content).contains("test-cluster")
        assertThat(content).contains("10.0.1.1")
        configFile.delete()
    }

    @Test
    fun `execute writes the common keys and the VM placement, exactly as before`() {
        WriteConfig().execute()

        val configFile = File(context.workingDirectory, "cassandra.patch.yaml")
        val yaml = ObjectMapper(YAMLFactory()).readTree(configFile)
        configFile.delete()

        assertThat(yaml.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "cluster_name",
            "num_tokens",
            "seed_provider",
            "hints_directory",
            "data_file_directories",
            "commitlog_directory",
            "concurrent_reads",
            "concurrent_writes",
            "trickle_fsync",
            "endpoint_snitch",
        )
        assertThat(yaml["num_tokens"].asInt()).isEqualTo(4)
        assertThat(yaml["seed_provider"]["class_name"].asText()).isEqualTo("org.apache.cassandra.locator.SimpleSeedProvider")
        assertThat(yaml["seed_provider"]["parameters"]["seeds"].asText()).isEqualTo("10.0.1.1")
        assertThat(yaml["data_file_directories"].map { it.asText() }).containsExactly("/mnt/db1/cassandra/data")
        assertThat(yaml["endpoint_snitch"].asText()).isEqualTo("Ec2Snitch")
        assertThat(yaml["trickle_fsync"].asBoolean()).isTrue()
    }

    @Test
    fun `execute uses custom token count`() {
        val command = WriteConfig()
        command.tokens = 8
        command.execute()

        val configFile = File(context.workingDirectory, "cassandra.patch.yaml")
        assertThat(configFile).exists()
        val content = configFile.readText()
        assertThat(content).contains("8")
        configFile.delete()
    }
}
