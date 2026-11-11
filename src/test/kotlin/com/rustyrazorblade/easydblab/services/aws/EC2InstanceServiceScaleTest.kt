package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.CreatedInstance
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest
import software.amazon.awssdk.services.ec2.model.CreateTagsResponse
import software.amazon.awssdk.services.ec2.model.DescribeInstanceStatusRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstanceStatusResponse
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceState
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.InstanceStatus
import software.amazon.awssdk.services.ec2.model.InstanceStatusSummary
import software.amazon.awssdk.services.ec2.model.Placement
import software.amazon.awssdk.services.ec2.model.Reservation
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse
import software.amazon.awssdk.services.ec2.model.SummaryStatus
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * [EC2InstanceService] at 1,000 nodes: every EC2 call stays within what the API accepts, launches
 * are batched per subnet with deterministic aliases, a launched instance is reported the moment
 * RunInstances returns it, throttling is retried with the same idempotency token, and the waits
 * hold up under eventual consistency.
 *
 * The EC2 client is mocked at the SDK boundary; each stub answers from the request it receives.
 */
class EC2InstanceServiceScaleTest {
    private val ec2: Ec2Client = mock()
    private val service =
        EC2InstanceService(
            ec2,
            EventBus(),
            region = "us-west-2",
            pollInterval = Duration.ofMillis(1),
            throttleBackoff = Duration.ofMillis(1),
        )
    private val launchedIds = AtomicInteger()

    init {
        whenever(ec2.describeInstancesPaginator(any<DescribeInstancesRequest>())).thenAnswer {
            DescribeInstancesIterable(ec2, it.getArgument(0))
        }
        whenever(ec2.createTags(any<CreateTagsRequest>())).thenReturn(CreateTagsResponse.builder().build())
    }

    private fun config(
        count: Int,
        startIndex: Int = 0,
        subnets: List<String> = listOf("subnet-a"),
    ) = InstanceCreationConfig(
        serverType = ServerType.Cassandra,
        count = count,
        instanceType = "i4i.xlarge",
        amiId = "ami-123",
        keyName = "key",
        securityGroupId = "sg-1",
        subnetIds = subnets,
        iamInstanceProfile = "profile",
        ebsConfig = null,
        tags = mapOf("easy_cass_lab" to "1", "ClusterId" to "c-1"),
        clusterName = "lab",
        startIndex = startIndex,
    )

    /** RunInstances answers with exactly the count asked for, each with a fresh id and launch index. */
    private fun runInstancesLaunchesWhatItIsAsked() {
        whenever(ec2.runInstances(any<RunInstancesRequest>())).thenAnswer { invocation ->
            val request = invocation.getArgument<RunInstancesRequest>(0)
            val instances =
                (0 until request.maxCount())
                    .map { launchIndex ->
                        Instance
                            .builder()
                            .instanceId("i-${launchedIds.incrementAndGet()}")
                            .amiLaunchIndex(launchIndex)
                            .privateIpAddress("10.0.0.${launchedIds.get()}")
                            .placement(Placement.builder().availabilityZone("az-${request.subnetId()}").build())
                            .build()
                    }.reversed() // AWS does not promise order; the launch index does.
            RunInstancesResponse.builder().instances(instances).build()
        }
    }

    private fun notFound(): Ec2Exception =
        Ec2Exception
            .builder()
            .statusCode(400)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(Constants.AWS.EC2_INSTANCE_NOT_FOUND).build())
            .build() as Ec2Exception

    private fun throttled(): Ec2Exception =
        Ec2Exception
            .builder()
            .statusCode(503)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("RequestLimitExceeded").build())
            .build() as Ec2Exception

    private fun running(id: String): Instance =
        Instance
            .builder()
            .instanceId(id)
            .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
            .publicIpAddress("54.0.0.1")
            .privateIpAddress("10.0.0.1")
            .placement(Placement.builder().availabilityZone("us-west-2a").build())
            .build()

    private fun reservation(vararg instances: Instance) =
        DescribeInstancesResponse
            .builder()
            .reservations(Reservation.builder().instances(*instances).build())
            .build()

    @Test
    fun `instances go one RunInstances per subnet, with the round-robin aliases continuing from the start index`() {
        runInstancesLaunchesWhatItIsAsked()

        val created = service.createInstances(config(count = 7, startIndex = 2, subnets = listOf("subnet-a", "subnet-b", "subnet-c")))

        // index i goes to subnet i % 3: a gets 3 and 6, b gets 4 and 7, c gets 2, 5 and 8.
        assertThat(created.associate { it.alias to it.availabilityZone }).isEqualTo(
            mapOf(
                "db2" to "az-subnet-c",
                "db3" to "az-subnet-a",
                "db4" to "az-subnet-b",
                "db5" to "az-subnet-c",
                "db6" to "az-subnet-a",
                "db7" to "az-subnet-b",
                "db8" to "az-subnet-c",
            ),
        )
        val requests = argumentCaptor<RunInstancesRequest>()
        verify(ec2, times(3)).runInstances(requests.capture())
        assertThat(requests.allValues.map { it.subnetId() to it.maxCount() })
            .containsExactly("subnet-c" to 3, "subnet-a" to 2, "subnet-b" to 2)
        assertThat(requests.allValues).allSatisfy { assertThat(it.minCount()).isEqualTo(it.maxCount()) }
    }

    @Test
    fun `each instance is named by its alias in the order of its launch index`() {
        runInstancesLaunchesWhatItIsAsked()

        val created = service.createInstances(config(count = 3))

        val tags = argumentCaptor<CreateTagsRequest>()
        verify(ec2, times(3)).createTags(tags.capture())
        val nameById = tags.allValues.associate { it.resources().single() to it.tags().single { t -> t.key() == "Name" }.value() }
        assertThat(nameById).isEqualTo(created.associate { it.instanceId to it.alias })
        // Launch index 0 is db0: the ids were handed out in launch-index order.
        assertThat(created.sortedBy { it.alias }.map { it.instanceId }).containsExactly("i-1", "i-2", "i-3")
    }

    @Test
    fun `the launch tags carry the cluster and server type so discovery finds every instance`() {
        runInstancesLaunchesWhatItIsAsked()

        service.createInstances(config(count = 2))

        val requests = argumentCaptor<RunInstancesRequest>()
        verify(ec2).runInstances(requests.capture())
        val byType =
            requests.firstValue.tagSpecifications().associate {
                it.resourceType() to
                    it.tags().associate { t -> t.key() to t.value() }
            }
        assertThat(byType.keys).containsExactlyInAnyOrder(ResourceType.INSTANCE, ResourceType.VOLUME)
        assertThat(byType.getValue(ResourceType.INSTANCE))
            .containsEntry("ClusterId", "c-1")
            .containsEntry("ServerType", "db")
            .containsEntry("Cluster", "lab")
            .doesNotContainKey("Name")
    }

    @Test
    fun `a group larger than one RunInstances chunk is split into chunks`() {
        runInstancesLaunchesWhatItIsAsked()

        val created = service.createInstances(config(count = Constants.Scale.RUN_INSTANCES_CHUNK * 2 + 50))

        val requests = argumentCaptor<RunInstancesRequest>()
        verify(ec2, times(3)).runInstances(requests.capture())
        assertThat(requests.allValues.map { it.maxCount() })
            .containsExactly(Constants.Scale.RUN_INSTANCES_CHUNK, Constants.Scale.RUN_INSTANCES_CHUNK, 50)
        assertThat(created.map { it.alias }.toSet()).hasSize(Constants.Scale.RUN_INSTANCES_CHUNK * 2 + 50)
    }

    @Test
    fun `every launched instance is reported before a later launch fails, so it reaches state`() {
        val reported = mutableListOf<CreatedInstance>()
        var calls = 0
        whenever(ec2.runInstances(any<RunInstancesRequest>())).thenAnswer { invocation ->
            calls++
            check(calls == 1) { "InsufficientInstanceCapacity" }
            val request = invocation.getArgument<RunInstancesRequest>(0)
            RunInstancesResponse
                .builder()
                .instances(
                    (0 until request.maxCount()).map {
                        Instance
                            .builder()
                            .instanceId("i-$it")
                            .amiLaunchIndex(it)
                            .placement(Placement.builder().availabilityZone("us-west-2a").build())
                            .build()
                    },
                ).build()
        }

        assertThatThrownBy {
            service.createInstances(config(count = 2, subnets = listOf("subnet-a", "subnet-b"))) { reported += it }
        }.hasMessageContaining("InsufficientInstanceCapacity")

        assertThat(reported.map { it.alias to it.instanceId }).containsExactly("db0" to "i-0")
    }

    @Test
    fun `a throttled RunInstances is retried with the same client token, so it launches once`() {
        var calls = 0
        whenever(ec2.runInstances(any<RunInstancesRequest>())).thenAnswer { invocation ->
            calls++
            if (calls == 1) throw throttled()
            RunInstancesResponse
                .builder()
                .instances(
                    Instance
                        .builder()
                        .instanceId("i-1")
                        .amiLaunchIndex(0)
                        .placement(Placement.builder().availabilityZone("us-west-2a").build())
                        .build(),
                ).build()
        }

        val created = service.createInstances(config(count = 1))

        assertThat(created.map { it.instanceId }).containsExactly("i-1")
        val requests = argumentCaptor<RunInstancesRequest>()
        verify(ec2, times(2)).runInstances(requests.capture())
        assertThat(requests.firstValue.clientToken()).isNotBlank().isEqualTo(requests.secondValue.clientToken())
    }

    @Test
    fun `a name tag on an instance EC2 does not see yet is retried`() {
        runInstancesLaunchesWhatItIsAsked()
        var tagCalls = 0
        whenever(ec2.createTags(any<CreateTagsRequest>())).thenAnswer {
            tagCalls++
            if (tagCalls == 1) throw notFound()
            CreateTagsResponse.builder().build()
        }

        service.createInstances(config(count = 1))

        verify(ec2, times(2)).createTags(any<CreateTagsRequest>())
    }

    @Test
    fun `the status check names at most 100 instances per request`() {
        whenever(ec2.describeInstanceStatus(any<DescribeInstanceStatusRequest>())).thenAnswer { invocation ->
            val ok = InstanceStatusSummary.builder().status(SummaryStatus.OK).build()
            val request = invocation.getArgument<DescribeInstanceStatusRequest>(0)
            DescribeInstanceStatusResponse
                .builder()
                .instanceStatuses(
                    request.instanceIds().map {
                        InstanceStatus
                            .builder()
                            .instanceId(it)
                            .instanceStatus(ok)
                            .systemStatus(ok)
                            .build()
                    },
                ).build()
        }

        service.waitForInstanceStatusOk((0 until 250).map { "i-$it" })

        val requests = argumentCaptor<DescribeInstanceStatusRequest>()
        verify(ec2, atLeastOnce()).describeInstanceStatus(requests.capture())
        assertThat(requests.allValues.map { it.instanceIds().size }).containsExactly(100, 100, 50)
        assertThat(requests.allValues.flatMap { it.instanceIds() }.toSet()).hasSize(250)
    }

    @Test
    fun `the running wait tolerates ids EC2 does not know yet and a response missing some of them`() {
        whenever(ec2.describeInstances(any<DescribeInstancesRequest>()))
            .thenThrow(notFound())
            .thenReturn(reservation(running("i-1")))
            .thenReturn(reservation(running("i-2")))

        val details = service.waitForInstancesRunning(listOf("i-1", "i-2"))

        assertThat(details.map { it.instanceId }).containsExactly("i-1", "i-2")
        val requests = argumentCaptor<DescribeInstancesRequest>()
        verify(ec2, times(3)).describeInstances(requests.capture())
        // Once i-1 answered running, only i-2 was asked about again.
        assertThat(requests.thirdValue.instanceIds()).containsExactly("i-2")
    }

    @Test
    fun `the running wait fails naming the instance that never appeared`() {
        whenever(ec2.describeInstances(any<DescribeInstancesRequest>())).thenReturn(reservation(running("i-1")))

        assertThatThrownBy { service.waitForInstancesRunning(listOf("i-1", "i-2"), timeoutMs = 100) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("i-2")
            .hasMessageNotContaining("i-1,")
    }

    @Test
    fun `discovery reads every page and counts pending instances as existing`() {
        fun tagged(
            id: String,
            alias: String,
            state: InstanceStateName,
        ) = Instance
            .builder()
            .instanceId(id)
            .state(InstanceState.builder().name(state).build())
            .placement(Placement.builder().availabilityZone("us-west-2a").build())
            .tags(
                Tag
                    .builder()
                    .key("Name")
                    .value(alias)
                    .build(),
                Tag
                    .builder()
                    .key("ServerType")
                    .value("db")
                    .build(),
            ).build()
        whenever(ec2.describeInstances(any<DescribeInstancesRequest>()))
            .thenReturn(reservation(tagged("i-1", "db0", InstanceStateName.RUNNING)).toBuilder().nextToken("page-2").build())
            .thenReturn(reservation(tagged("i-2", "db1", InstanceStateName.PENDING)))

        val found = service.findInstancesByClusterId("c-1")

        assertThat(found[ServerType.Cassandra]?.map { it.alias }).containsExactly("db0", "db1")
        val requests = argumentCaptor<DescribeInstancesRequest>()
        verify(ec2, times(2)).describeInstances(requests.capture())
        val states =
            requests.firstValue
                .filters()
                .single { it.name() == "instance-state-name" }
                .values()
        assertThat(states).containsExactlyInAnyOrder("running", "pending")
    }
}
