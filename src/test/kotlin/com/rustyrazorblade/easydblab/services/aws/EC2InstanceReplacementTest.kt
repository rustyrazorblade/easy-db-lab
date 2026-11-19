package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest
import software.amazon.awssdk.services.ec2.model.CreateTagsResponse
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceState
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.Placement
import software.amazon.awssdk.services.ec2.model.Reservation
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest
import software.amazon.awssdk.services.ec2.model.TerminateInstancesResponse
import java.time.Duration

/**
 * [EC2InstanceService]'s two calls for replacing one node (design D6): a launch of exactly one
 * instance under the node's ordinal, in the subnet that ordinal maps to, with the ordinal tags
 * that give its alias; and a terminate that returns only once EC2 no longer counts the instance
 * as pending or running. The EC2 client is mocked at the SDK boundary.
 */
class EC2InstanceReplacementTest {
    private val ec2: Ec2Client = mock()
    private val service =
        EC2InstanceService(
            ec2,
            EventBus(),
            region = "us-west-2",
            pollInterval = Duration.ofMillis(1),
            throttleBackoff = Duration.ofMillis(1),
        )
    private val subnets = listOf("subnet-a", "subnet-b", "subnet-c")

    private fun config(serverType: ServerType) =
        InstanceCreationConfig(
            serverType = serverType,
            indexes = (0 until 7).toList(),
            instanceType = "c6id.xlarge",
            amiId = "ami-123",
            keyName = "key",
            securityGroupId = "sg-1",
            subnetIds = subnets,
            iamInstanceProfile = "profile",
            ebsConfig = null,
            tags = mapOf("ClusterId" to "c-1"),
            clusterName = "lab",
        )

    private fun launchesOne() {
        whenever(ec2.runInstances(any<RunInstancesRequest>())).thenAnswer {
            val request = it.getArgument<RunInstancesRequest>(0)
            val instance =
                Instance
                    .builder()
                    .instanceId("i-new")
                    .amiLaunchIndex(0)
                    .privateIpAddress("10.0.0.9")
                    .placement(Placement.builder().availabilityZone("az-${request.subnetId()}").build())
                    .build()
            RunInstancesResponse.builder().instances(instance).build()
        }
        whenever(ec2.createTags(any<CreateTagsRequest>())).thenReturn(CreateTagsResponse.builder().build())
    }

    @ParameterizedTest
    @CsvSource("Cassandra, 0, subnet-a", "Cassandra, 2, subnet-c", "Cassandra, 4, subnet-b", "Stress, 6, subnet-a")
    fun `one instance launches under the ordinal's alias, in the subnet the ordinal maps to, tagged with that ordinal`(
        serverType: ServerType,
        ordinal: Int,
        subnet: String,
    ) {
        launchesOne()
        val requests = argumentCaptor<RunInstancesRequest>()

        val created = service.launchOne(config(serverType), ordinal)

        verify(ec2).runInstances(requests.capture())
        assertThat(requests.allValues).hasSize(1)
        val request = requests.firstValue
        assertThat(request.minCount()).isEqualTo(1)
        assertThat(request.maxCount()).isEqualTo(1)
        assertThat(request.subnetId()).isEqualTo(subnet)
        val tags =
            request
                .tagSpecifications()
                .single { it.resourceType() == ResourceType.INSTANCE }
                .tags()
                .associate { it.key() to it.value() }
        assertThat(OrdinalRun.ordinalOf(tags, launchIndex = 0)).isEqualTo(ordinal)
        assertThat(created.alias).isEqualTo("${serverType.serverType}$ordinal")
        assertThat(created.instanceId).isEqualTo("i-new")
        assertThat(created.availabilityZone).isEqualTo("az-$subnet")
    }

    private fun describedAs(vararg states: InstanceStateName?) {
        val responses =
            states.map { state ->
                val reservations =
                    if (state == null) {
                        emptyList()
                    } else {
                        listOf(
                            Reservation
                                .builder()
                                .instances(
                                    Instance
                                        .builder()
                                        .instanceId("i-old")
                                        .placement(Placement.builder().availabilityZone("us-west-2a").build())
                                        .state(InstanceState.builder().name(state).build())
                                        .build(),
                                ).build(),
                        )
                    }
                DescribeInstancesResponse.builder().reservations(reservations).build()
            }
        whenever(ec2.describeInstances(any<DescribeInstancesRequest>())).thenReturn(responses.first(), *responses.drop(1).toTypedArray())
    }

    @Test
    fun `terminate waits until EC2 no longer reports the instance as pending or running`() {
        whenever(ec2.terminateInstances(any<TerminateInstancesRequest>())).thenReturn(TerminateInstancesResponse.builder().build())
        describedAs(InstanceStateName.RUNNING, InstanceStateName.RUNNING, InstanceStateName.SHUTTING_DOWN)

        service.terminateAndWait("i-old", Duration.ofSeconds(5))

        inOrder(ec2) {
            verify(ec2).terminateInstances(any<TerminateInstancesRequest>())
            verify(ec2, times(3)).describeInstances(any<DescribeInstancesRequest>())
        }
    }

    @Test
    fun `terminate of an instance EC2 no longer returns is done at once`() {
        whenever(ec2.terminateInstances(any<TerminateInstancesRequest>())).thenReturn(TerminateInstancesResponse.builder().build())
        describedAs(null)

        service.terminateAndWait("i-old", Duration.ofSeconds(5))
    }

    @Test
    fun `terminate fails naming the instance when it is still running at the deadline`() {
        whenever(ec2.terminateInstances(any<TerminateInstancesRequest>())).thenReturn(TerminateInstancesResponse.builder().build())
        describedAs(InstanceStateName.RUNNING)

        assertThatThrownBy { service.terminateAndWait("i-old", Duration.ofMillis(50)) }
            .hasMessageContaining("i-old")
            .hasMessageContaining("running")
    }
}
