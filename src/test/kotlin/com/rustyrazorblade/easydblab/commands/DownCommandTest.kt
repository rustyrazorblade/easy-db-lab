package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DownCommandTest : BaseKoinTest() {
    private lateinit var mockTeardownService: AwsInfrastructureService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    private val testControlHost =
        ClusterHost(
            publicIp = "54.1.2.5",
            privateIp = "10.0.1.3",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control0",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(testControlHost),
                ),
        ).apply {
            updateInfrastructure(
                InfrastructureState(
                    vpcId = "vpc-test123",
                    region = "us-west-2",
                    subnetIds = listOf("subnet-test"),
                    securityGroupId = "sg-test",
                ),
            )
        }

    private val testDiscoveredResources =
        DiscoveredResources(
            vpcId = "vpc-test123",
            vpcName = "test-cluster",
            instanceIds = listOf("i-control0"),
            securityGroupIds = listOf("sg-test"),
            subnetIds = listOf("subnet-test"),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<AwsInfrastructureService> { mockTeardownService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockTeardownService = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
    }

    @Nested
    inner class CurrentClusterMode {
        @Test
        fun `execute reports error when no cluster state`() {
            whenever(mockClusterStateManager.exists()).thenReturn(false)

            val command = Down()
            command.autoApprove = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("No cluster state found")
        }

        @Test
        fun `execute reports error when no VPC in cluster state`() {
            val stateNoVpc =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                )
            whenever(mockClusterStateManager.load()).thenReturn(stateNoVpc)

            val command = Down()
            command.autoApprove = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("No VPC ID")
        }

        @Test
        fun `execute previews resources in dry-run mode`() {
            whenever(mockTeardownService.teardownVpc(eq("vpc-test123"), eq(true)))
                .thenReturn(TeardownResult.success(testDiscoveredResources))

            val command = Down()
            command.dryRun = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("DRY RUN")
            assertThat(output).contains("vpc-test123")
        }

        @Test
        fun `execute tears down VPC with auto-approve`() {
            whenever(mockTeardownService.teardownVpc(eq("vpc-test123"), eq(true)))
                .thenReturn(TeardownResult.success(testDiscoveredResources))
            whenever(mockTeardownService.teardownVpc(eq("vpc-test123"), eq(false)))
                .thenReturn(TeardownResult.success(testDiscoveredResources))

            val command = Down()
            command.autoApprove = true
            command.execute()

            verify(mockTeardownService).teardownVpc("vpc-test123", false)

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Teardown completed successfully")
        }

        @Test
        fun `execute updates cluster state on successful teardown`() {
            whenever(mockTeardownService.teardownVpc(eq("vpc-test123"), eq(true)))
                .thenReturn(TeardownResult.success(testDiscoveredResources))
            whenever(mockTeardownService.teardownVpc(eq("vpc-test123"), eq(false)))
                .thenReturn(TeardownResult.success(testDiscoveredResources))

            val command = Down()
            command.autoApprove = true
            command.execute()

            verify(mockClusterStateManager).save(any())
        }

        @Test
        fun `execute reports no resources when VPC is empty`() {
            whenever(mockTeardownService.teardownVpc(eq("vpc-test123"), eq(true)))
                .thenReturn(TeardownResult(success = true, resourcesDeleted = emptyList()))

            val command = Down()
            command.autoApprove = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("No resources found")
        }
    }

    @Nested
    inner class SpecificVpcMode {
        @Test
        fun `execute tears down specific VPC`() {
            whenever(mockTeardownService.teardownVpc(eq("vpc-specific"), eq(true)))
                .thenReturn(
                    TeardownResult.success(
                        DiscoveredResources(vpcId = "vpc-specific", instanceIds = listOf("i-1")),
                    ),
                )
            whenever(mockTeardownService.teardownVpc(eq("vpc-specific"), eq(false)))
                .thenReturn(TeardownResult.success(DiscoveredResources(vpcId = "vpc-specific")))

            val command = Down()
            command.vpcId = "vpc-specific"
            command.autoApprove = true
            command.execute()

            verify(mockTeardownService).teardownVpc("vpc-specific", false)
        }
    }

    @Nested
    inner class AllTaggedMode {
        @Test
        fun `execute finds and reports tagged VPCs`() {
            val resources =
                listOf(
                    DiscoveredResources(vpcId = "vpc-1", vpcName = "cluster-1"),
                    DiscoveredResources(vpcId = "vpc-2", vpcName = "cluster-2"),
                )
            whenever(mockTeardownService.teardownAllTagged(eq(true), any()))
                .thenReturn(TeardownResult.success(resources))

            val command = Down()
            command.teardownAll = true
            command.dryRun = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("DRY RUN")
            assertThat(output).contains("vpc-1")
            assertThat(output).contains("vpc-2")
        }

        @Test
        fun `execute reports no VPCs found`() {
            whenever(mockTeardownService.teardownAllTagged(eq(true), any()))
                .thenReturn(TeardownResult(success = true, resourcesDeleted = emptyList()))

            val command = Down()
            command.teardownAll = true
            command.autoApprove = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("No tagged VPCs found")
        }
    }

    @Nested
    inner class PackerMode {
        @Test
        fun `execute tears down packer infrastructure`() {
            val packerResources =
                DiscoveredResources(vpcId = "vpc-packer", vpcName = "packer")
            whenever(mockTeardownService.teardownPackerInfrastructure(eq(true)))
                .thenReturn(TeardownResult.success(packerResources))
            whenever(mockTeardownService.teardownPackerInfrastructure(eq(false)))
                .thenReturn(TeardownResult.success(packerResources))

            val command = Down()
            command.teardownPacker = true
            command.autoApprove = true
            command.execute()

            verify(mockTeardownService).teardownPackerInfrastructure(false)
        }

        @Test
        fun `execute reports no packer VPC found`() {
            whenever(mockTeardownService.teardownPackerInfrastructure(eq(true)))
                .thenReturn(TeardownResult(success = true, resourcesDeleted = emptyList()))

            val command = Down()
            command.teardownPacker = true
            command.autoApprove = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("No packer VPC found")
        }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `execute reports errors on failed teardown`() {
            whenever(mockTeardownService.teardownVpc(eq("vpc-test123"), eq(true)))
                .thenReturn(TeardownResult.success(testDiscoveredResources))
            whenever(mockTeardownService.teardownVpc(eq("vpc-test123"), eq(false)))
                .thenReturn(TeardownResult.failure(listOf("Failed to delete SG", "Timeout on instance")))

            val command = Down()
            command.autoApprove = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("completed with errors")
            assertThat(output).contains("Failed to delete SG")
            assertThat(output).contains("Timeout on instance")
        }
    }
}
