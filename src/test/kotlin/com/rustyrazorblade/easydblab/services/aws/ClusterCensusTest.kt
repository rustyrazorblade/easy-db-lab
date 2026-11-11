package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest
import software.amazon.awssdk.services.ec2.model.DescribeVpcsResponse
import software.amazon.awssdk.services.ec2.model.Region
import software.amazon.awssdk.services.ec2.model.Vpc
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * How `down` counts the clusters that still use the account bucket: the tagged VPCs naming the
 * bucket, in every enabled region, with every region asked at once.
 */
class ClusterCensusTest {
    private val bucket = "easy-db-lab-acct"
    private val requests: MutableList<DescribeVpcsRequest> = Collections.synchronizedList(mutableListOf())

    private fun regionalEc2(
        vpcs: Map<String, List<String>>,
        beforeAnswer: () -> Unit = {},
    ) = object : RegionalClients {
        override fun ec2(region: String): Ec2Client =
            mock<Ec2Client>().also { client ->
                doCallRealMethod().whenever(client).describeVpcsPaginator(any<DescribeVpcsRequest>())
                whenever(client.describeVpcs(any<DescribeVpcsRequest>())).thenAnswer { invocation ->
                    requests += invocation.getArgument<DescribeVpcsRequest>(0)
                    beforeAnswer()
                    DescribeVpcsResponse
                        .builder()
                        .vpcs(vpcs[region].orEmpty().map { Vpc.builder().vpcId(it).build() })
                        .build()
                }
            }

        override fun ecs(region: String) = error("not used")

        override fun logs(region: String) = error("not used")
    }

    private fun profileEc2(vararg regions: String) =
        mock<Ec2Client>().also {
            whenever(it.describeRegions()).thenReturn(
                DescribeRegionsResponse.builder().regions(regions.map { name -> Region.builder().regionName(name).build() }).build(),
            )
        }

    @Test
    fun `every region's tagged VPCs that name the bucket are counted, with their region`() {
        val census =
            ClusterCensus(
                profileEc2("us-west-2", "eu-west-1", "ap-south-1"),
                regionalEc2(mapOf("us-west-2" to listOf("vpc-a"), "eu-west-1" to listOf("vpc-b", "vpc-c"))),
            )

        assertThat(census.clusterVpcs(bucket)).containsExactlyInAnyOrder(
            ClusterVpc("us-west-2", "vpc-a"),
            ClusterVpc("eu-west-1", "vpc-b"),
            ClusterVpc("eu-west-1", "vpc-c"),
        )
        assertThat(requests).hasSize(3).allSatisfy { request ->
            assertThat(request.filters().associate { it.name() to it.values() })
                .containsOnly(entry("tag:easy_cass_lab", listOf("1")), entry("tag:bucket", listOf(bucket)))
        }
    }

    /** Each region's call waits until every region has been asked: asked one after the other, it would time out. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `the regions are asked at the same time`() {
        val regions = arrayOf("us-west-2", "eu-west-1")
        val asked = CountDownLatch(regions.size)
        val census =
            ClusterCensus(
                profileEc2(*regions),
                regionalEc2(emptyMap()) {
                    asked.countDown()
                    check(asked.await(5, TimeUnit.SECONDS)) { "The regions were asked one after the other" }
                },
            )

        assertThat(census.clusterVpcs(bucket)).isEmpty()
    }
}
