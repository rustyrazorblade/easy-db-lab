package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.events.EventBus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateRouteRequest
import software.amazon.awssdk.services.ec2.model.DescribeRouteTablesRequest
import software.amazon.awssdk.services.ec2.model.DescribeRouteTablesResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.RouteTable

/**
 * Unit tests for EC2VpcService error handling and race condition behavior
 * that can't be tested with LocalStack.
 *
 * For core CRUD and idempotent find-or-create operations, see EC2VpcServiceIntegrationTest.
 * Security group behavior is tested in EC2SecurityGroupServiceTest.
 */
internal class EC2VpcServiceTest {
    private val mockEc2Client: Ec2Client = mock()
    private val eventBus = EventBus()
    private val vpcService = EC2VpcService(mockEc2Client, eventBus)

    @Test
    fun `ensureRouteTable should throw exception when no main route table found`() {
        val emptyResponse =
            DescribeRouteTablesResponse
                .builder()
                .routeTables(emptyList())
                .build()

        whenever(mockEc2Client.describeRouteTables(any<DescribeRouteTablesRequest>())).thenReturn(emptyResponse)

        assertThrows<IllegalStateException> {
            vpcService.ensureRouteTable("vpc-12345", "subnet-12345", "igw-12345")
        }
    }

    @Test
    fun `ensureRouteTable should handle route already exists exception`() {
        val routeTable =
            RouteTable
                .builder()
                .routeTableId("rtb-12345")
                .routes(emptyList())
                .build()

        val describeResponse =
            DescribeRouteTablesResponse
                .builder()
                .routeTables(routeTable)
                .build()

        val ec2Exception =
            Ec2Exception
                .builder()
                .awsErrorDetails(
                    software.amazon.awssdk.awscore.exception.AwsErrorDetails
                        .builder()
                        .errorCode("RouteAlreadyExists")
                        .build(),
                ).build()

        whenever(mockEc2Client.describeRouteTables(any<DescribeRouteTablesRequest>())).thenReturn(describeResponse)
        whenever(mockEc2Client.createRoute(any<CreateRouteRequest>())).thenThrow(ec2Exception)

        // Should not throw exception - should handle duplicate gracefully
        vpcService.ensureRouteTable("vpc-12345", "subnet-12345", "igw-12345")

        verify(mockEc2Client).createRoute(any<CreateRouteRequest>())
    }

    @Test
    fun `ensureRouteTable should throw on non-route-exists exception`() {
        val routeTable =
            RouteTable
                .builder()
                .routeTableId("rtb-12345")
                .routes(emptyList())
                .build()

        val describeResponse =
            DescribeRouteTablesResponse
                .builder()
                .routeTables(routeTable)
                .build()

        val ec2Exception =
            Ec2Exception
                .builder()
                .awsErrorDetails(
                    software.amazon.awssdk.awscore.exception.AwsErrorDetails
                        .builder()
                        .errorCode("InvalidRouteTableID.NotFound")
                        .build(),
                ).build()

        whenever(mockEc2Client.describeRouteTables(any<DescribeRouteTablesRequest>())).thenReturn(describeResponse)
        whenever(mockEc2Client.createRoute(any<CreateRouteRequest>())).thenThrow(ec2Exception)

        assertThrows<Ec2Exception> {
            vpcService.ensureRouteTable("vpc-12345", "subnet-12345", "igw-12345")
        }
    }
}
