package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class InitTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.exists()).thenReturn(false)
    }

    @AfterEach
    fun cleanup() {
        File("setup_instance.sh").delete()
        File("cassandra").deleteRecursively()
        File("k8s").deleteRecursively()
    }

    @Nested
    inner class Validation {
        @Test
        fun `execute fails with zero cassandra instances`() {
            val command = Init()
            command.clean = true
            command.cassandraInstances = 0

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("positive")
        }

        @Test
        fun `execute fails with negative stress instances`() {
            val command = Init()
            command.clean = true
            command.stressInstances = -1

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("negative")
        }

        @Test
        fun `execute fails with invalid CIDR`() {
            val command = Init()
            command.clean = true
            command.cidr = "invalid-cidr"

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class SuccessfulInit {
        @Test
        fun `execute creates cluster state with defaults`() {
            val command = Init()
            command.clean = true
            command.name = "my-cluster"
            command.execute()

            verify(mockClusterStateManager).save(
                argThat {
                    name == "my-cluster" &&
                        initConfig?.cassandraInstances == 3 &&
                        initConfig?.stressInstances == 0 &&
                        initConfig?.region == "us-west-2"
                },
            )
        }

        @Test
        fun `execute creates cluster state with custom options`() {
            val command = Init()
            command.clean = true
            command.name = "custom-cluster"
            command.cassandraInstances = 6
            command.stressInstances = 2
            command.instanceType = "i3.2xlarge"
            command.execute()

            verify(mockClusterStateManager).save(
                argThat {
                    name == "custom-cluster" &&
                        initConfig?.cassandraInstances == 6 &&
                        initConfig?.stressInstances == 2 &&
                        initConfig?.instanceType == "i3.2xlarge"
                },
            )
        }

        @Test
        fun `execute extracts resource files`() {
            val command = Init()
            command.clean = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Writing setup_instance.sh")
            assertThat(output).contains("sidecar config")
            assertThat(output).contains("Kubernetes manifests")
            assertThat(File("setup_instance.sh")).exists()
            assertThat(File("cassandra/cassandra-sidecar.yaml")).exists()
        }

        @Test
        fun `execute displays completion message`() {
            val command = Init()
            command.clean = true
            command.name = "test-cluster"
            command.cassandraInstances = 3
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("3 Cassandra instances")
            assertThat(output).contains("us-west-2")
        }

        @Test
        fun `execute shows next step hint when not starting`() {
            val command = Init()
            command.clean = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("easy-db-lab up")
        }
    }

    @Nested
    inner class ExistingVpc {
        @Test
        fun `execute sets existing VPC ID when provided`() {
            val command = Init()
            command.clean = true
            command.existingVpcId = "vpc-existing123"
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Using existing VPC: vpc-existing123")

            // save is called twice: once in prepareEnvironment, once after setting VPC
            verify(mockClusterStateManager, atLeastOnce()).save(
                argThat { vpcId == "vpc-existing123" },
            )
        }
    }

    @Nested
    inner class EbsOptions {
        @Test
        fun `execute fails with zero EBS size`() {
            val command = Init()
            command.clean = true
            command.ebsSize = 0

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("positive")
        }

        @Test
        fun `execute fails with negative EBS IOPS`() {
            val command = Init()
            command.clean = true
            command.ebsIops = -1

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("negative")
        }
    }
}
