package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure
import com.rustyrazorblade.easydblab.providers.aws.withEcsRoleRetry
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.AssignPublicIp
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration
import software.amazon.awssdk.services.ecs.model.CreateClusterRequest
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest
import software.amazon.awssdk.services.ecs.model.DeploymentConfiguration
import software.amazon.awssdk.services.ecs.model.LaunchType
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest

/**
 * The account compactor: one ECS Fargate service per AWS account, in the account bucket's region,
 * that compacts the shared observability store. Every resource is found by name; nothing is kept
 * in the cluster state.
 */
interface CompactorService {
    /**
     * Makes sure the service runs: creates it when missing, starts it at 1 task when stopped, and
     * leaves a running service as it is. Does not wait for the task to run.
     */
    fun ensureRunning(bucket: String)

    /** Sets the service's desired count to 0. Does nothing but report it when there is no service. */
    fun stop(bucket: String)

    /**
     * Stops the service when no cluster VPC in any enabled region still names [bucket], leaving
     * out the VPCs in [tornDown].
     */
    fun stopIfLastCluster(
        bucket: String,
        tornDown: Set<String>,
    )

    /** The service's state, its current or last task, and that task's recent log lines. Changes nothing. */
    fun status(bucket: String): CompactorStatus
}

/**
 * [CompactorService] over ECS, CloudWatch Logs, IAM and EC2, with clients for the bucket's region.
 * It runs the service's lifecycle and composes the parts that do one thing each: [CompactorIam] and
 * [CompactorNetwork] find or create what the task needs, [CompactorTaskRegistrar] registers its
 * task definition, and [CompactorStatusReader] reads its status.
 */
class DefaultCompactorService(
    private val s3: S3Client,
    private val iam: CompactorIam,
    private val network: CompactorNetwork,
    private val census: ClusterCensus,
    private val regionalClients: RegionalClients,
    private val eventBus: EventBus,
    private val registrar: CompactorTaskRegistrar = CompactorTaskRegistrar(),
    private val statusReader: CompactorStatusReader = CompactorStatusReader(regionalClients),
) : CompactorService {
    private companion object {
        /** S3 reports the original region as an empty location constraint. */
        const val DEFAULT_BUCKET_REGION = "us-east-1"

        /** A deployment may run at most the desired count: never two tasks at once. */
        const val MAXIMUM_PERCENT = 100
    }

    override fun ensureRunning(bucket: String) {
        val region = bucketRegion(bucket)
        val roles = iam.ensure()
        val vpc = network.ensure(region)
        regionalClients.logs(region).use { ensureLogGroup(it) }
        regionalClients.ecs(region).use { ecs ->
            ecs.createCluster(CreateClusterRequest.builder().clusterName(Constants.Compactor.ECS_CLUSTER).build())
            val definition = CompactorTaskDefinition(bucket, region, roles.taskRoleArn, roles.executionRoleArn)
            val service = ecs.describeCompactorService()
            when {
                service == null -> {
                    val arn = registrar.register(ecs, definition)
                    withEcsRoleRetry("create-compactor-service") { ecs.createService(createRequest(arn, vpc)) }
                    eventBus.emit(Event.Compactor.Started(region, arn))
                }
                service.desiredCount() == 0 -> {
                    val arn = registrar.latestOrRegister(ecs, definition)
                    ecs.updateService(update().taskDefinition(arn).desiredCount(1).build())
                    eventBus.emit(Event.Compactor.Started(region, arn))
                }
                // A started service is left as it is: a new configuration takes effect on the next start.
                else -> reportStarted(ecs, region, service)
            }
        }
    }

    /** Reports a service that asks for a task as running, starting, or failing, as `status` reads it. */
    private fun reportStarted(
        ecs: EcsClient,
        region: String,
        service: Service,
    ) {
        val state =
            StartedState.of(service.runningCount(), service.pendingCount()) {
                ecs.latestCompactorTask()?.lastStatus().orEmpty()
            }
        val event =
            when (state) {
                StartedState.RUNNING -> Event.Compactor.AlreadyRunning(region, service.runningCount())
                StartedState.STARTING -> Event.Compactor.Starting(region, service.pendingCount())
                StartedState.FAILING -> Event.Compactor.NoTaskRunning(region, service.desiredCount())
            }
        eventBus.emit(event)
    }

    override fun stop(bucket: String) {
        val region = bucketRegion(bucket)
        regionalClients.ecs(region).use { ecs ->
            if (ecs.describeCompactorService() == null) {
                eventBus.emit(Event.Compactor.NotCreated(region))
            } else {
                ecs.updateService(update().desiredCount(0).build())
                eventBus.emit(Event.Compactor.Stopped(region))
            }
        }
    }

    override fun stopIfLastCluster(
        bucket: String,
        tornDown: Set<String>,
    ) {
        when (val decision = CompactorShutdownPolicy.decide(census.clusterVpcs(bucket), tornDown)) {
            CompactorShutdownPolicy.Decision.Stop -> stop(bucket)
            is CompactorShutdownPolicy.Decision.Keep ->
                eventBus.emit(Event.Compactor.KeptRunning(decision.remaining.map { "${it.region}/${it.vpcId}" }))
        }
    }

    override fun status(bucket: String): CompactorStatus = statusReader.read(bucketRegion(bucket))

    /** The account bucket's region, from `GetBucketLocation`. */
    private fun bucketRegion(bucket: String): String =
        s3
            .getBucketLocation(GetBucketLocationRequest.builder().bucket(bucket).build())
            .locationConstraintAsString()
            .orEmpty()
            .ifEmpty { DEFAULT_BUCKET_REGION }

    private fun ensureLogGroup(logs: CloudWatchLogsClient) {
        try {
            // No retention policy: the compactor's logs are kept.
            logs.createLogGroup(CreateLogGroupRequest.builder().logGroupName(Constants.Compactor.LOG_GROUP).build())
        } catch (_: ResourceAlreadyExistsException) {
            // Already there.
        }
    }

    private fun update(): UpdateServiceRequest.Builder =
        UpdateServiceRequest
            .builder()
            .cluster(Constants.Compactor.ECS_CLUSTER)
            .service(Constants.Compactor.SERVICE)

    private fun createRequest(
        taskDefinitionArn: String,
        vpc: VpcInfrastructure,
    ): CreateServiceRequest =
        CreateServiceRequest
            .builder()
            .cluster(Constants.Compactor.ECS_CLUSTER)
            .serviceName(Constants.Compactor.SERVICE)
            .taskDefinition(taskDefinitionArn)
            .desiredCount(1)
            .launchType(LaunchType.FARGATE)
            // Never two tasks: a new one starts only after the old one stopped.
            .deploymentConfiguration(
                DeploymentConfiguration
                    .builder()
                    .maximumPercent(MAXIMUM_PERCENT)
                    .minimumHealthyPercent(0)
                    .build(),
            ).networkConfiguration(
                NetworkConfiguration
                    .builder()
                    .awsvpcConfiguration(
                        AwsVpcConfiguration
                            .builder()
                            .subnets(vpc.subnetIds)
                            .securityGroups(vpc.securityGroupId)
                            .assignPublicIp(AssignPublicIp.ENABLED)
                            .build(),
                    ).build(),
            ).build()
}
