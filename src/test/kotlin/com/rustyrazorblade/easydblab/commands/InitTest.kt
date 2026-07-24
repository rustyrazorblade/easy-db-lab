package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceTypeCapabilities
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class InitTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockEc2InstanceService: EC2InstanceService
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { mockEc2InstanceService }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        mockEc2InstanceService = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.exists()).thenReturn(false)
        // Default: every instance type is x86_64 with instance store.
        whenever(mockEc2InstanceService.describeInstanceType(any()))
            .thenReturn(InstanceTypeCapabilities(hasInstanceStore = true, supportedArchitectures = listOf("x86_64")))
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
        fun `execute stores explicit cidr in state`() {
            val command = Init()
            command.clean = true
            command.cidr = "172.16.0.0/16"
            command.execute()

            verify(mockClusterStateManager).save(
                argThat { initConfig?.cidr == "172.16.0.0/16" },
            )
        }

        @Test
        fun `execute stores null cidr when --cidr is omitted`() {
            val command = Init()
            command.clean = true
            command.execute()

            verify(mockClusterStateManager).save(
                argThat { initConfig?.cidr == null },
            )
        }

        @Test
        fun `execute extracts resource files`() {
            val command = Init()
            command.clean = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Writing setup_instance.sh")
            assertThat(File("setup_instance.sh")).exists()
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
    inner class NamespacedPrecedence {
        @Test
        fun `namespaced db instance-type wins over legacy alias regardless of order`() {
            val namespacedFirst = Init()
            picocli.CommandLine(namespacedFirst).parseArgs(
                "--db.instance-type",
                "arm.big",
                "--instance",
                "legacy.small",
            )
            assertThat(namespacedFirst.resolvedDbInstanceType).isEqualTo("arm.big")

            val legacyFirst = Init()
            picocli.CommandLine(legacyFirst).parseArgs(
                "--instance",
                "legacy.small",
                "--db.instance-type",
                "arm.big",
            )
            assertThat(legacyFirst.resolvedDbInstanceType).isEqualTo("arm.big")
        }

        @Test
        fun `namespaced app count wins over legacy alias regardless of order`() {
            val namespacedFirst = Init()
            picocli.CommandLine(namespacedFirst).parseArgs("--app.count", "7", "--app", "2")
            assertThat(namespacedFirst.resolvedAppCount).isEqualTo(7)

            val legacyFirst = Init()
            picocli.CommandLine(legacyFirst).parseArgs("--app", "2", "--app.count", "7")
            assertThat(legacyFirst.resolvedAppCount).isEqualTo(7)
        }

        @Test
        fun `legacy alias sets the value when the namespaced option is absent`() {
            val command = Init()
            picocli.CommandLine(command).parseArgs("--cassandra", "5", "--stress-instance", "z1.big")
            assertThat(command.resolvedDbCount).isEqualTo(5)
            assertThat(command.resolvedAppInstanceType).isEqualTo("z1.big")
        }

        @Test
        fun `defaults are used when neither namespaced nor legacy is set`() {
            val command = Init()
            picocli.CommandLine(command).parseArgs()
            assertThat(command.resolvedDbCount).isEqualTo(command.cassandraInstances)
            assertThat(command.resolvedAppCount).isEqualTo(command.stressInstances)
            assertThat(command.resolvedDbInstanceType).isEqualTo(command.instanceType)
            assertThat(command.resolvedAppInstanceType).isEqualTo(command.stressInstanceType)
        }
    }

    @Nested
    inner class ArchitectureDerivation {
        @Test
        fun `derives each group architecture independently from its resolved instance type`() {
            whenever(mockEc2InstanceService.describeInstanceType("arm.db"))
                .thenReturn(InstanceTypeCapabilities(hasInstanceStore = true, supportedArchitectures = listOf("arm64")))
            whenever(mockEc2InstanceService.describeInstanceType("x86.app"))
                .thenReturn(InstanceTypeCapabilities(hasInstanceStore = false, supportedArchitectures = listOf("x86_64")))

            val command = Init()
            command.clean = true
            command.instanceType = "arm.db"
            command.stressInstanceType = "x86.app"
            command.execute()

            verify(mockClusterStateManager).save(
                argThat {
                    initConfig?.dbArch == "ARM64" &&
                        initConfig?.appArch == "AMD64" &&
                        initConfig?.controlArch == "AMD64"
                },
            )
        }

        @Test
        fun `fails fast when the instance type is unknown in the region naming the type and region`() {
            whenever(mockEc2InstanceService.describeInstanceType("bogus.type"))
                .thenThrow(IllegalStateException("Instance type 'bogus.type' not found in region us-west-2"))

            val command = Init()
            command.clean = true
            command.instanceType = "bogus.type"

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("bogus.type")
                .hasMessageContaining("us-west-2")

            // No cluster state saved with instance config when derivation fails.
            verify(mockClusterStateManager, org.mockito.kotlin.never()).save(
                argThat { initConfig != null },
            )
        }
    }

    @Nested
    inner class CniSelection {
        @Test
        fun `defaults the persisted CNI to Flannel`() {
            val command = Init()
            command.clean = true
            command.execute()

            verify(mockClusterStateManager).save(
                argThat { initConfig?.cni == CniMode.Flannel },
            )
        }

        @Test
        fun `persists Cilium when --cni cilium is selected`() {
            val command = Init()
            command.clean = true
            command.cni = CniMode.Cilium
            command.execute()

            verify(mockClusterStateManager).save(
                argThat { initConfig?.cni == CniMode.Cilium },
            )
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
    inner class CniOptions {
        @Test
        fun `execute persists Flannel as the default cni when --cni is omitted`() {
            val command = Init()
            command.clean = true
            command.execute()

            verify(mockClusterStateManager).save(
                argThat { initConfig?.cni == CniMode.Flannel },
            )
        }

        @Test
        fun `execute persists Cilium when cni is set to Cilium`() {
            val command = Init()
            command.clean = true
            command.cni = CniMode.Cilium
            command.execute()

            verify(mockClusterStateManager).save(
                argThat { initConfig?.cni == CniMode.Cilium },
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
