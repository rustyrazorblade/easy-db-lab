package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.InfrastructureConfig
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for AwsInfrastructureService.
 *
 * Verifies resource discovery, correct teardown order, error handling,
 * and support for different teardown modes.
 */
class AwsInfrastructureServiceTest {
    private lateinit var vpcService: VpcService
    private lateinit var emrService: EMRService
    private lateinit var openSearchService: OpenSearchService
    private lateinit var outputHandler: OutputHandler
    private lateinit var service: AwsInfrastructureService

    @BeforeEach
    fun setup() {
        vpcService = mock()
        emrService = mock()
        openSearchService = mock()
        outputHandler = mock()
        service =
            AwsInfrastructureService(
                vpcService = vpcService,
                emrService = emrService,
                openSearchService = openSearchService,
                outputHandler = outputHandler,
            )
    }

    @Nested
    inner class TeardownVpc {
        @Test
        fun `dryRun should not delete any resources`() {
            val vpcId = "vpc-12345"
            setupEmptyVpcMocks(vpcId)

            val result = service.teardownVpc(vpcId, dryRun = true)

            assertThat(result.success).isTrue()
            verify(vpcService, never()).terminateInstances(any())
            verify(vpcService, never()).deleteVpc(any())
        }

        @Test
        fun `should use default dryRun false when not specified`() {
            val vpcId = "vpc-12345"
            setupEmptyVpcMocks(vpcId)

            // Call without specifying dryRun to use default value (false)
            val result = service.teardownVpc(vpcId)

            assertThat(result.success).isTrue()
            // Default dryRun=false should result in actual deletion
            verify(vpcService).deleteVpc(vpcId)
        }

        @Test
        fun `should delete resources in correct order`() {
            val vpcId = "vpc-12345"
            val instanceIds = listOf("i-1")
            val subnetIds = listOf("subnet-1")
            val sgIds = listOf("sg-1")
            val igwId = "igw-1"

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(instanceIds)
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(subnetIds)
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(sgIds)
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(igwId)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isTrue()
            // Verify instances terminated (Phase 1)
            verify(vpcService).terminateInstances(instanceIds)
            verify(vpcService).waitForInstancesTerminated(instanceIds)
            // Verify ENI wait (Phase 2)
            verify(vpcService).waitForNetworkInterfacesCleared(vpcId)
            // Verify security groups cleaned up (Phase 3)
            verify(vpcService).revokeSecurityGroupRules("sg-1")
            verify(vpcService).deleteSecurityGroup("sg-1")
            // Verify subnets deleted
            verify(vpcService).deleteSubnet("subnet-1")
            // Verify IGW deleted
            verify(vpcService).detachInternetGateway(igwId, vpcId)
            verify(vpcService).deleteInternetGateway(igwId)
            // Verify VPC deleted
            verify(vpcService).deleteVpc(vpcId)
        }

        @Test
        fun `EMR failure should not prevent other Phase 1 tasks from running`() {
            val vpcId = "vpc-12345"
            val emrIds = listOf("j-123")
            val instanceIds = listOf("i-1")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(instanceIds)
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emrIds)
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(emrService.terminateClusters(emrIds))
                .thenThrow(RuntimeException("EMR termination failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isFalse()
            assertThat(result.errors).anyMatch { it.contains("EMR") }
            // EC2 instances should still be terminated (parallel execution)
            verify(vpcService).terminateInstances(instanceIds)
            // Phase 3 should NOT run because Phase 1 had a fatal failure
            verify(vpcService, never()).deleteVpc(any())
        }

        @Test
        fun `should stop on EC2 termination failure`() {
            val vpcId = "vpc-12345"
            val instanceIds = listOf("i-1")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(instanceIds)
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(vpcService.terminateInstances(instanceIds))
                .thenThrow(RuntimeException("Instance termination failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isFalse()
            assertThat(result.errors).anyMatch { it.contains("Instance") || it.contains("terminate") }
            // Should not proceed to delete VPC
            verify(vpcService, never()).deleteVpc(any())
        }

        @Test
        fun `should continue on non-critical resource failures`() {
            val vpcId = "vpc-12345"

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(listOf("sg-1"))
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            // Security group deletion fails - non-critical
            whenever(vpcService.deleteSecurityGroup("sg-1"))
                .thenThrow(RuntimeException("SG deletion failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            // Should still try to delete VPC
            verify(vpcService).deleteVpc(vpcId)
            // But result should indicate errors
            assertThat(result.errors).isNotEmpty()
        }

        @Test
        fun `should successfully terminate EMR clusters`() {
            val vpcId = "vpc-12345"
            val emrIds = listOf("j-123", "j-456")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emrIds)
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isTrue()
            verify(emrService).terminateClusters(emrIds)
            verify(emrService).waitForClustersTerminated(emrIds)
            verify(vpcService).deleteVpc(vpcId)
        }

        @Test
        fun `should successfully delete OpenSearch domains`() {
            val vpcId = "vpc-12345"
            val domains = listOf("test-os-1", "test-os-2")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(domains)

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isTrue()
            verify(openSearchService).deleteDomain("test-os-1")
            verify(openSearchService).deleteDomain("test-os-2")
            verify(openSearchService).waitForDomainDeleted("test-os-1")
            verify(openSearchService).waitForDomainDeleted("test-os-2")
            verify(vpcService).deleteVpc(vpcId)
        }

        @Test
        fun `should stop on OpenSearch deletion failure`() {
            val vpcId = "vpc-12345"
            val domains = listOf("test-os")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(domains)
            whenever(openSearchService.waitForDomainDeleted("test-os"))
                .thenThrow(RuntimeException("Timeout waiting for domain deletion"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isFalse()
            assertThat(result.errors).anyMatch { it.contains("OpenSearch") || it.contains("Timeout") }
        }

        @Test
        fun `should handle OpenSearch domain delete failure but continue waiting for others`() {
            val vpcId = "vpc-12345"
            val domains = listOf("test-os-1", "test-os-2")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(domains)
            // First domain delete fails
            whenever(openSearchService.deleteDomain("test-os-1"))
                .thenThrow(RuntimeException("Delete failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            // Should still try to delete second domain
            verify(openSearchService).deleteDomain("test-os-2")
            // Should only wait for the one that was successfully initiated
            verify(openSearchService).waitForDomainDeleted("test-os-2")
            verify(openSearchService, never()).waitForDomainDeleted("test-os-1")
        }

        @Test
        fun `should delete NAT gateways`() {
            val vpcId = "vpc-12345"
            val natIds = listOf("nat-1", "nat-2")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(natIds)
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isTrue()
            verify(vpcService).deleteNatGateway("nat-1")
            verify(vpcService).deleteNatGateway("nat-2")
            verify(vpcService).waitForNatGatewaysDeleted(natIds)
        }

        @Test
        fun `should continue on NAT gateway deletion failure`() {
            val vpcId = "vpc-12345"
            val natIds = listOf("nat-1")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(natIds)
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(vpcService.deleteNatGateway("nat-1"))
                .thenThrow(RuntimeException("NAT deletion failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            // Should still try to delete VPC (NAT failure is non-fatal)
            verify(vpcService).deleteVpc(vpcId)
            assertThat(result.errors).anyMatch { it.contains("NAT") }
        }

        @Test
        fun `should delete route tables`() {
            val vpcId = "vpc-12345"
            val rtbIds = listOf("rtb-1", "rtb-2")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(rtbIds)
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isTrue()
            verify(vpcService).deleteRouteTable("rtb-1")
            verify(vpcService).deleteRouteTable("rtb-2")
        }

        @Test
        fun `should continue on route table deletion failure`() {
            val vpcId = "vpc-12345"
            val rtbIds = listOf("rtb-1")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(rtbIds)
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(vpcService.deleteRouteTable("rtb-1"))
                .thenThrow(RuntimeException("Route table deletion failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            verify(vpcService).deleteVpc(vpcId)
            assertThat(result.errors).anyMatch { it.contains("route table") }
        }

        @Test
        fun `should continue on subnet deletion failure`() {
            val vpcId = "vpc-12345"

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(vpcService.deleteSubnet("subnet-1"))
                .thenThrow(RuntimeException("Subnet deletion failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            verify(vpcService).deleteVpc(vpcId)
            assertThat(result.errors).anyMatch { it.contains("subnet") }
        }

        @Test
        fun `should continue on internet gateway deletion failure`() {
            val vpcId = "vpc-12345"
            val igwId = "igw-1"

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(igwId)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(vpcService.detachInternetGateway(igwId, vpcId))
                .thenThrow(RuntimeException("IGW detach failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            verify(vpcService).deleteVpc(vpcId)
            assertThat(result.errors).anyMatch { it.contains("internet gateway") }
        }

        @Test
        fun `should record error on VPC deletion failure`() {
            val vpcId = "vpc-12345"

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(vpcService.deleteVpc(vpcId))
                .thenThrow(RuntimeException("VPC deletion failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isFalse()
            assertThat(result.errors).anyMatch { it.contains("VPC") }
        }

        @Test
        fun `should continue on security group revoke failure`() {
            val vpcId = "vpc-12345"

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(listOf("sg-1"))
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(vpcService.revokeSecurityGroupRules("sg-1"))
                .thenThrow(RuntimeException("Revoke failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            verify(vpcService).deleteVpc(vpcId)
            assertThat(result.errors).anyMatch { it.contains("revoke") || it.contains("security group") }
        }

        @Test
        fun `should handle VPC with null name`() {
            val vpcId = "vpc-12345"

            // VPC name is null
            whenever(vpcService.getVpcName(vpcId)).thenReturn(null)
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isTrue()
            verify(vpcService).deleteVpc(vpcId)
        }

        @Test
        fun `should catch unexpected exceptions during teardown`() {
            val vpcId = "vpc-12345"

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            // Throw an exception on the "Tearing down" message to trigger the outer catch block
            // Use argThat to match only the teardown message, not the discovery message
            whenever(outputHandler.handleMessage(argThat { startsWith("\nTearing down") }))
                .thenThrow(RuntimeException("Unexpected output failure"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isFalse()
            assertThat(result.errors).anyMatch { it.contains("Unexpected error") }
        }

        @Test
        fun `ENI wait timeout should be non-fatal`() {
            val vpcId = "vpc-12345"

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
            whenever(vpcService.waitForNetworkInterfacesCleared(vpcId))
                .thenThrow(RuntimeException("ENI timeout"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            // Should still try to delete VPC despite ENI timeout
            verify(vpcService).deleteVpc(vpcId)
            assertThat(result.errors).anyMatch { it.contains("network interfaces") }
        }

        @Test
        fun `should collect errors from multiple parallel Phase 1 failures`() {
            val vpcId = "vpc-12345"
            val emrIds = listOf("j-123")
            val instanceIds = listOf("i-1")
            val domains = listOf("test-os")

            whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
            whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(instanceIds)
            whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1"))
            whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
            whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
            whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
            whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emrIds)
            whenever(openSearchService.findDomainsInVpc(any())).thenReturn(domains)
            // All three critical Phase 1 tasks fail
            whenever(emrService.terminateClusters(emrIds))
                .thenThrow(RuntimeException("EMR failed"))
            whenever(vpcService.terminateInstances(instanceIds))
                .thenThrow(RuntimeException("EC2 failed"))
            whenever(openSearchService.deleteDomain("test-os"))
                .thenThrow(RuntimeException("OpenSearch failed"))

            val result = service.teardownVpc(vpcId, dryRun = false)

            assertThat(result.success).isFalse()
            assertThat(result.errors).anyMatch { it.contains("EMR") }
            assertThat(result.errors).anyMatch { it.contains("EC2") || it.contains("terminate") || it.contains("instances") }
            assertThat(result.errors).anyMatch { it.contains("OpenSearch") }
        }
    }

    @Nested
    inner class TeardownAllTagged {
        @Test
        fun `should return success when no VPCs found`() {
            whenever(vpcService.findVpcsByTag(any(), any())).thenReturn(emptyList())

            val result = service.teardownAllTagged(dryRun = false)

            assertThat(result.success).isTrue()
            assertThat(result.resourcesDeleted).isEmpty()
        }

        @Test
        fun `should use default parameters when not specified`() {
            whenever(vpcService.findVpcsByTag(any(), any())).thenReturn(emptyList())

            // Call without specifying dryRun or includePackerVpc to use defaults
            val result = service.teardownAllTagged()

            assertThat(result.success).isTrue()
        }

        @Test
        fun `dryRun should discover resources but not delete anything`() {
            val vpcIds = listOf("vpc-1", "vpc-2")

            whenever(vpcService.findVpcsByTag(any(), any())).thenReturn(vpcIds)

            // Setup vpc-1
            whenever(vpcService.getVpcName("vpc-1")).thenReturn("test-cluster-1")
            setupEmptyResourcesFor("vpc-1")

            // Setup vpc-2
            whenever(vpcService.getVpcName("vpc-2")).thenReturn("test-cluster-2")
            setupEmptyResourcesFor("vpc-2")

            val result = service.teardownAllTagged(dryRun = true)

            assertThat(result.success).isTrue()
            assertThat(result.resourcesDeleted).hasSize(2)
            // Should NOT delete anything in dryRun mode
            verify(vpcService, never()).deleteVpc(any())
            verify(vpcService, never()).terminateInstances(any())
        }

        @Test
        fun `should collect errors from multiple VPC teardowns`() {
            val vpcIds = listOf("vpc-1", "vpc-2")

            whenever(vpcService.findVpcsByTag(any(), any())).thenReturn(vpcIds)

            // Setup vpc-1 with a VPC deletion error
            whenever(vpcService.getVpcName("vpc-1")).thenReturn("test-cluster-1")
            setupEmptyResourcesFor("vpc-1")
            whenever(vpcService.deleteVpc("vpc-1"))
                .thenThrow(RuntimeException("VPC-1 deletion failed"))

            // Setup vpc-2 with a VPC deletion error
            whenever(vpcService.getVpcName("vpc-2")).thenReturn("test-cluster-2")
            setupEmptyResourcesFor("vpc-2")
            whenever(vpcService.deleteVpc("vpc-2"))
                .thenThrow(RuntimeException("VPC-2 deletion failed"))

            val result = service.teardownAllTagged(dryRun = false)

            assertThat(result.success).isFalse()
            // Should have errors from both VPCs
            assertThat(result.errors).hasSize(2)
            assertThat(result.errors).anyMatch { it.contains("VPC-1") }
            assertThat(result.errors).anyMatch { it.contains("VPC-2") }
        }

        @Test
        fun `should find and teardown all tagged VPCs`() {
            val vpcIds = listOf("vpc-1", "vpc-2")

            whenever(vpcService.findVpcsByTag(any(), any())).thenReturn(vpcIds)

            // Setup vpc-1 as a normal cluster VPC
            whenever(vpcService.getVpcName("vpc-1")).thenReturn("test-cluster")
            setupEmptyResourcesFor("vpc-1")

            // Setup vpc-2 as another normal cluster VPC
            whenever(vpcService.getVpcName("vpc-2")).thenReturn("test-cluster-2")
            setupEmptyResourcesFor("vpc-2")

            val result = service.teardownAllTagged(dryRun = false)

            assertThat(result.success).isTrue()
            verify(vpcService).deleteVpc("vpc-1")
            verify(vpcService).deleteVpc("vpc-2")
        }

        @Test
        fun `should skip packer VPC unless includePackerVpc is true`() {
            val vpcIds = listOf("vpc-packer", "vpc-cluster")

            whenever(vpcService.findVpcsByTag(any(), any())).thenReturn(vpcIds)

            // Setup packer VPC
            whenever(vpcService.getVpcName("vpc-packer")).thenReturn(InfrastructureConfig.PACKER_VPC_NAME)
            setupEmptyResourcesFor("vpc-packer")

            // Setup normal cluster VPC
            whenever(vpcService.getVpcName("vpc-cluster")).thenReturn("test-cluster")
            setupEmptyResourcesFor("vpc-cluster")

            val result = service.teardownAllTagged(dryRun = false, includePackerVpc = false)

            assertThat(result.success).isTrue()
            // Packer VPC should be skipped
            verify(vpcService, never()).deleteVpc("vpc-packer")
            // Cluster VPC should be deleted
            verify(vpcService).deleteVpc("vpc-cluster")
        }

        @Test
        fun `should include packer VPC when includePackerVpc is true`() {
            val vpcIds = listOf("vpc-packer")

            whenever(vpcService.findVpcsByTag(any(), any())).thenReturn(vpcIds)

            // Setup packer VPC
            whenever(vpcService.getVpcName("vpc-packer")).thenReturn(InfrastructureConfig.PACKER_VPC_NAME)
            setupEmptyResourcesFor("vpc-packer")

            val result = service.teardownAllTagged(dryRun = false, includePackerVpc = true)

            assertThat(result.success).isTrue()
            verify(vpcService).deleteVpc("vpc-packer")
        }
    }

    @Nested
    inner class TeardownPackerInfrastructure {
        @Test
        fun `should find and teardown packer VPC`() {
            val packerVpcId = "vpc-packer"

            whenever(vpcService.findVpcByName(InfrastructureConfig.PACKER_VPC_NAME))
                .thenReturn(packerVpcId)
            whenever(vpcService.getVpcName(packerVpcId)).thenReturn(InfrastructureConfig.PACKER_VPC_NAME)
            setupEmptyResourcesFor(packerVpcId)

            val result = service.teardownPackerInfrastructure(dryRun = false)

            assertThat(result.success).isTrue()
            verify(vpcService).deleteVpc(packerVpcId)
        }

        @Test
        fun `should use default dryRun false when not specified`() {
            val packerVpcId = "vpc-packer"

            whenever(vpcService.findVpcByName(InfrastructureConfig.PACKER_VPC_NAME))
                .thenReturn(packerVpcId)
            whenever(vpcService.getVpcName(packerVpcId)).thenReturn(InfrastructureConfig.PACKER_VPC_NAME)
            setupEmptyResourcesFor(packerVpcId)

            // Call without specifying dryRun to use default (false)
            val result = service.teardownPackerInfrastructure()

            assertThat(result.success).isTrue()
            verify(vpcService).deleteVpc(packerVpcId)
        }

        @Test
        fun `should return success when no packer VPC exists`() {
            whenever(vpcService.findVpcByName(InfrastructureConfig.PACKER_VPC_NAME))
                .thenReturn(null)

            val result = service.teardownPackerInfrastructure(dryRun = false)

            assertThat(result.success).isTrue()
            assertThat(result.resourcesDeleted).isEmpty()
            verify(vpcService, never()).deleteVpc(any())
        }

        @Test
        fun `dryRun should discover packer VPC resources but not delete anything`() {
            val packerVpcId = "vpc-packer"

            whenever(vpcService.findVpcByName(InfrastructureConfig.PACKER_VPC_NAME))
                .thenReturn(packerVpcId)
            whenever(vpcService.getVpcName(packerVpcId)).thenReturn(InfrastructureConfig.PACKER_VPC_NAME)
            setupEmptyResourcesFor(packerVpcId)

            val result = service.teardownPackerInfrastructure(dryRun = true)

            assertThat(result.success).isTrue()
            assertThat(result.resourcesDeleted).hasSize(1)
            // Should NOT delete anything in dryRun mode
            verify(vpcService, never()).deleteVpc(any())
        }
    }

    // Helper methods

    private fun setupEmptyVpcMocks(vpcId: String) {
        whenever(vpcService.getVpcName(vpcId)).thenReturn("test-vpc")
        setupEmptyResourcesFor(vpcId)
    }

    private fun setupEmptyResourcesFor(vpcId: String) {
        whenever(vpcService.findInstancesInVpc(vpcId)).thenReturn(emptyList())
        whenever(vpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
        whenever(vpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
        whenever(vpcService.findNatGatewaysInVpc(vpcId)).thenReturn(emptyList())
        whenever(vpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)
        whenever(vpcService.findRouteTablesInVpc(vpcId)).thenReturn(emptyList())
        whenever(emrService.findClustersInVpc(eq(vpcId), any())).thenReturn(emptyList())
        whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
    }
}
