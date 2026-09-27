package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest
import software.amazon.awssdk.services.ecs.model.CreateServiceResponse
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse
import software.amazon.awssdk.services.ecs.model.InvalidParameterException
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.Tag
import software.amazon.awssdk.services.ecs.model.TaskDefinition
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse

/**
 * What `up`, `down` and the compactor commands do to the ECS service in each state it can be in:
 * missing, stopped at 0, or running. ECS, S3, IAM and EC2 are faked at their client boundary; the
 * task definition is the real one.
 */
class CompactorServiceTest {
    private val bucket = "easy-db-lab-acct"
    private val ecs = mock<EcsClient>()
    private val s3 = mock<S3Client>()
    private val census = mock<ClusterCensus>()
    private val events = mutableListOf<Event>()
    private val registered = "arn:aws:ecs:eu-west-1:1:task-definition/easy-db-lab-compactor:7"
    private var ecsRegion = "eu-west-1"

    private val service =
        DefaultCompactorService(
            s3 = s3,
            iam = mock<CompactorIam>().also { whenever(it.ensure()).thenReturn(CompactorRoles("arn:task", "arn:exec")) },
            network =
                mock<CompactorNetwork>().also {
                    whenever(it.ensure(any())).thenReturn(VpcInfrastructure("vpc-c", listOf("subnet-c"), "sg-c", "igw-c"))
                },
            census = census,
            regionalClients =
                object : RegionalClients {
                    override fun ec2(region: String) = error("not used")

                    override fun ecs(region: String) = ecs.also { assertThat(region).isEqualTo(ecsRegion) }

                    override fun logs(region: String) = mock<CloudWatchLogsClient>()
                },
            eventBus =
                EventBus().also {
                    it.addListener(
                        object : EventListener {
                            override fun onEvent(envelope: EventEnvelope) {
                                events += envelope.event
                            }

                            override fun close() = Unit
                        },
                    )
                },
        )

    @BeforeEach
    fun registration() {
        bucketIn("eu-west-1")
        whenever(ecs.registerTaskDefinition(any<RegisterTaskDefinitionRequest>())).thenReturn(
            RegisterTaskDefinitionResponse
                .builder()
                .taskDefinition(TaskDefinition.builder().taskDefinitionArn(registered).build())
                .build(),
        )
    }

    private fun bucketIn(locationConstraint: String) {
        whenever(s3.getBucketLocation(any<GetBucketLocationRequest>()))
            .thenReturn(GetBucketLocationResponse.builder().locationConstraint(locationConstraint).build())
    }

    private fun serviceIs(vararg services: Service) {
        whenever(ecs.describeServices(any<DescribeServicesRequest>()))
            .thenReturn(DescribeServicesResponse.builder().services(*services).build())
    }

    private fun service(
        desired: Int,
        status: String = "ACTIVE",
    ) = Service
        .builder()
        .serviceName(Constants.Compactor.SERVICE)
        .status(status)
        .desiredCount(desired)
        .build()

    @Test
    fun `a missing service is created with one task that never overlaps another, in the bucket's region`() {
        serviceIs()

        service.ensureRunning(bucket)

        val created = argumentCaptor<CreateServiceRequest>()
        verify(ecs).createService(created.capture())
        with(created.firstValue) {
            assertThat(desiredCount()).isEqualTo(1)
            assertThat(deploymentConfiguration().maximumPercent()).isEqualTo(100)
            assertThat(deploymentConfiguration().minimumHealthyPercent()).isEqualTo(0)
            assertThat(taskDefinition()).isEqualTo(registered)
            assertThat(networkConfiguration().awsvpcConfiguration().subnets()).containsExactly("subnet-c")
        }
        assertThat(events).contains(Event.Compactor.Started("eu-west-1", registered))
    }

    /** In a fresh account ECS cannot assume the roles IAM has just created for a few seconds. */
    @Test
    fun `a service create that ECS refuses while the new roles propagate is retried`() {
        serviceIs()
        whenever(ecs.createService(any<CreateServiceRequest>()))
            .thenThrow(InvalidParameterException.builder().message("Unable to assume the service linked role.").build())
            .thenReturn(CreateServiceResponse.builder().build())

        service.ensureRunning(bucket)

        verify(ecs, times(2)).createService(any<CreateServiceRequest>())
        assertThat(events).contains(Event.Compactor.Started("eu-west-1", registered))
    }

    @Test
    fun `a bucket in the original region, reported as an empty location, runs the compactor in us-east-1`() {
        bucketIn("")
        ecsRegion = "us-east-1"
        serviceIs()

        service.ensureRunning(bucket)

        assertThat(events).contains(Event.Compactor.Started("us-east-1", registered))
    }

    @Test
    fun `an inactive service is created again`() {
        serviceIs(service(desired = 0, status = "INACTIVE"))

        service.ensureRunning(bucket)

        verify(ecs).createService(any<CreateServiceRequest>())
    }

    @Test
    fun `a stopped service starts on the latest revision when its configuration is unchanged`() {
        serviceIs(service(desired = 0))
        val latest = "arn:aws:ecs:eu-west-1:1:task-definition/easy-db-lab-compactor:6"
        val hash = registeredRequest().tags().single().value()
        whenever(ecs.describeTaskDefinition(any<DescribeTaskDefinitionRequest>())).thenReturn(
            DescribeTaskDefinitionResponse
                .builder()
                .taskDefinition(TaskDefinition.builder().taskDefinitionArn(latest).build())
                .tags(
                    Tag
                        .builder()
                        .key(Constants.Compactor.CONFIG_HASH_TAG)
                        .value(hash)
                        .build(),
                ).build(),
        )

        service.ensureRunning(bucket)

        val updated = argumentCaptor<UpdateServiceRequest>()
        verify(ecs).updateService(updated.capture())
        assertThat(updated.firstValue.desiredCount()).isEqualTo(1)
        assertThat(updated.firstValue.taskDefinition()).isEqualTo(latest)
        verify(ecs, never()).registerTaskDefinition(any<RegisterTaskDefinitionRequest>())
    }

    @Test
    fun `a stopped service whose configuration changed starts on a new revision`() {
        serviceIs(service(desired = 0))
        whenever(ecs.describeTaskDefinition(any<DescribeTaskDefinitionRequest>())).thenReturn(
            DescribeTaskDefinitionResponse
                .builder()
                .taskDefinition(TaskDefinition.builder().taskDefinitionArn("old").build())
                .tags(
                    Tag
                        .builder()
                        .key(Constants.Compactor.CONFIG_HASH_TAG)
                        .value("stale")
                        .build(),
                ).build(),
        )

        service.ensureRunning(bucket)

        val updated = argumentCaptor<UpdateServiceRequest>()
        verify(ecs).updateService(updated.capture())
        assertThat(updated.firstValue.taskDefinition()).isEqualTo(registered)
    }

    @Test
    fun `a running service is left as it is`() {
        serviceIs(service(desired = 1))

        service.ensureRunning(bucket)

        verify(ecs, never()).updateService(any<UpdateServiceRequest>())
        verify(ecs, never()).createService(any<CreateServiceRequest>())
        verify(ecs, never()).registerTaskDefinition(any<RegisterTaskDefinitionRequest>())
        assertThat(events).contains(Event.Compactor.AlreadyRunning("eu-west-1"))
    }

    @Test
    fun `the last cluster's down sets the desired count to 0 and reports the stop`() {
        serviceIs(service(desired = 1))
        whenever(census.clusterVpcs(bucket)).thenReturn(listOf(ClusterVpc("us-west-2", "vpc-this")))

        service.stopIfLastCluster(bucket, tornDown = setOf("vpc-this"))

        val updated = argumentCaptor<UpdateServiceRequest>()
        verify(ecs).updateService(updated.capture())
        assertThat(updated.firstValue.desiredCount()).isEqualTo(0)
        assertThat(events).containsExactly(Event.Compactor.Stopped("eu-west-1"))
    }

    @Test
    fun `another cluster that uses the bucket keeps the compactor running`() {
        serviceIs(service(desired = 1))
        whenever(census.clusterVpcs(bucket)).thenReturn(listOf(ClusterVpc("us-west-2", "vpc-this"), ClusterVpc("eu-west-1", "vpc-b")))

        service.stopIfLastCluster(bucket, tornDown = setOf("vpc-this"))

        verify(ecs, never()).updateService(any<UpdateServiceRequest>())
        assertThat(events).containsExactly(Event.Compactor.KeptRunning(listOf("eu-west-1/vpc-b")))
    }

    @Test
    fun `a stop with no service updates nothing and does not report a stop`() {
        serviceIs()

        service.stop(bucket)

        verify(ecs, never()).updateService(any<UpdateServiceRequest>())
        assertThat(events).containsExactly(Event.Compactor.NotCreated("eu-west-1"))
    }

    @Test
    fun `the last cluster's down with no service updates nothing and does not report a stop`() {
        serviceIs(service(desired = 0, status = "INACTIVE"))
        whenever(census.clusterVpcs(bucket)).thenReturn(emptyList())

        service.stopIfLastCluster(bucket, tornDown = setOf("vpc-this"))

        verify(ecs, never()).updateService(any<UpdateServiceRequest>())
        assertThat(events).containsExactly(Event.Compactor.NotCreated("eu-west-1"))
    }

    /** The request `ensureRunning` would register for this bucket and these roles. */
    private fun registeredRequest() = CompactorTaskDefinition(bucket, "eu-west-1", "arn:task", "arn:exec").request()
}
