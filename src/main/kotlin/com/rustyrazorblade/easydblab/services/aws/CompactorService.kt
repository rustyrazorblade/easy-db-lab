package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.AssignPublicIp
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration
import software.amazon.awssdk.services.ecs.model.ClientException
import software.amazon.awssdk.services.ecs.model.ClusterNotFoundException
import software.amazon.awssdk.services.ecs.model.CreateClusterRequest
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest
import software.amazon.awssdk.services.ecs.model.DeploymentConfiguration
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest
import software.amazon.awssdk.services.ecs.model.DesiredStatus
import software.amazon.awssdk.services.ecs.model.LaunchType
import software.amazon.awssdk.services.ecs.model.ListTasksRequest
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.TaskDefinitionField
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest

/**
 * What `observability compactor status` shows.
 *
 * @property region the account bucket's region, where the service runs.
 * @property exists whether the service exists (and is not inactive).
 * @property desiredCount the tasks the service asks for.
 * @property runningCount the tasks that run.
 * @property taskId the current task, or the last stopped one; empty when there is none.
 * @property taskStatus that task's last status.
 * @property logLines the newest log lines of that task's containers, oldest first.
 */
data class CompactorStatus(
    val region: String,
    val exists: Boolean,
    val desiredCount: Int = 0,
    val runningCount: Int = 0,
    val taskId: String = "",
    val taskStatus: String = "",
    val logLines: List<String> = emptyList(),
)

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

    /** Sets the service's desired count to 0. */
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
 */
@Suppress("TooManyFunctions")
class DefaultCompactorService(
    private val s3: S3Client,
    private val iam: CompactorIam,
    private val network: CompactorNetwork,
    private val census: ClusterCensus,
    private val regionalClients: RegionalClients,
    private val eventBus: EventBus,
) : CompactorService {
    private companion object {
        const val INACTIVE = "INACTIVE"

        /** S3 reports the original region as an empty location constraint. */
        const val DEFAULT_BUCKET_REGION = "us-east-1"
    }

    override fun ensureRunning(bucket: String) {
        val region = bucketRegion(bucket)
        val roles = iam.ensure()
        val vpc = network.ensure(region)
        regionalClients.logs(region).use { ensureLogGroup(it) }
        regionalClients.ecs(region).use { ecs ->
            ecs.createCluster(CreateClusterRequest.builder().clusterName(Constants.Compactor.ECS_CLUSTER).build())
            val definition = CompactorTaskDefinition(bucket, region, roles.taskRoleArn, roles.executionRoleArn)
            val service = describe(ecs)
            when {
                service == null -> {
                    val arn = register(ecs, definition)
                    ecs.createService(createRequest(arn, vpc))
                    eventBus.emit(Event.Compactor.Started(region, arn))
                }
                service.desiredCount() == 0 -> {
                    val arn = latestOrRegister(ecs, definition)
                    ecs.updateService(
                        UpdateServiceRequest
                            .builder()
                            .cluster(Constants.Compactor.ECS_CLUSTER)
                            .service(Constants.Compactor.SERVICE)
                            .taskDefinition(arn)
                            .desiredCount(1)
                            .build(),
                    )
                    eventBus.emit(Event.Compactor.Started(region, arn))
                }
                // A running service is left as it is: a new configuration takes effect on the next start.
                else -> eventBus.emit(Event.Compactor.AlreadyRunning(region))
            }
        }
    }

    override fun stop(bucket: String) {
        val region = bucketRegion(bucket)
        regionalClients.ecs(region).use { ecs ->
            if (describe(ecs) != null) {
                ecs.updateService(
                    UpdateServiceRequest
                        .builder()
                        .cluster(Constants.Compactor.ECS_CLUSTER)
                        .service(Constants.Compactor.SERVICE)
                        .desiredCount(0)
                        .build(),
                )
            }
        }
        eventBus.emit(Event.Compactor.Stopped(region))
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

    override fun status(bucket: String): CompactorStatus {
        val region = bucketRegion(bucket)
        return regionalClients.ecs(region).use { ecs ->
            val service = describe(ecs) ?: return@use CompactorStatus(region, exists = false)
            val task = latestTask(ecs)
            val taskId = task?.taskArn()?.substringAfterLast('/').orEmpty()
            CompactorStatus(
                region = region,
                exists = true,
                desiredCount = service.desiredCount(),
                runningCount = service.runningCount(),
                taskId = taskId,
                taskStatus = task?.lastStatus().orEmpty(),
                logLines = if (taskId.isEmpty()) emptyList() else regionalClients.logs(region).use { recentLogLines(it, taskId) },
            )
        }
    }

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

    /** The service, or null when it does not exist or is inactive. */
    private fun describe(ecs: EcsClient): Service? =
        try {
            ecs
                .describeServices(
                    DescribeServicesRequest
                        .builder()
                        .cluster(Constants.Compactor.ECS_CLUSTER)
                        .services(Constants.Compactor.SERVICE)
                        .build(),
                ).services()
                .firstOrNull { it.status() != INACTIVE }
        } catch (_: ClusterNotFoundException) {
            null
        }

    private fun register(
        ecs: EcsClient,
        definition: CompactorTaskDefinition,
    ): String =
        ecs
            .registerTaskDefinition(definition.request())
            .taskDefinition()
            .taskDefinitionArn()

    /** The latest revision when it was built from the same configuration, or a newly registered one. */
    private fun latestOrRegister(
        ecs: EcsClient,
        definition: CompactorTaskDefinition,
    ): String {
        val latest =
            try {
                ecs.describeTaskDefinition(
                    DescribeTaskDefinitionRequest
                        .builder()
                        .taskDefinition(Constants.Compactor.TASK_FAMILY)
                        .include(TaskDefinitionField.TAGS)
                        .build(),
                )
            } catch (_: ClientException) {
                null
            }
        val latestHash = latest?.tags()?.firstOrNull { it.key() == Constants.Compactor.CONFIG_HASH_TAG }?.value()
        return if (latest != null && latestHash == definition.configHash()) {
            latest.taskDefinition().taskDefinitionArn()
        } else {
            register(ecs, definition)
        }
    }

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
                    .maximumPercent(100)
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

    /** The running task, or the most recently stopped one. */
    private fun latestTask(ecs: EcsClient) =
        listOf(DesiredStatus.RUNNING, DesiredStatus.STOPPED).firstNotNullOfOrNull { status ->
            val arns =
                ecs
                    .listTasks(
                        ListTasksRequest
                            .builder()
                            .cluster(Constants.Compactor.ECS_CLUSTER)
                            .serviceName(Constants.Compactor.SERVICE)
                            .desiredStatus(status)
                            .build(),
                    ).taskArns()
            if (arns.isEmpty()) {
                null
            } else {
                ecs
                    .describeTasks(
                        DescribeTasksRequest
                            .builder()
                            .cluster(Constants.Compactor.ECS_CLUSTER)
                            .tasks(arns)
                            .build(),
                    ).tasks()
                    .maxByOrNull { it.createdAt() }
            }
        }

    /** The newest log lines of every container of [taskId], merged by time, oldest first. */
    private fun recentLogLines(
        logs: CloudWatchLogsClient,
        taskId: String,
    ): List<String> =
        CompactorTaskDefinition.CONTAINERS
            .flatMap { container ->
                // awslogs names each stream <prefix>/<container>/<task id>; the prefix is the container name.
                val stream = "$container/$container/$taskId"
                try {
                    logs
                        .getLogEvents(
                            GetLogEventsRequest
                                .builder()
                                .logGroupName(Constants.Compactor.LOG_GROUP)
                                .logStreamName(stream)
                                .limit(Constants.Compactor.STATUS_LOG_LINES)
                                .startFromHead(false)
                                .build(),
                        ).events()
                        .map { it.timestamp() to "[$container] ${it.message()}" }
                } catch (_: ResourceNotFoundException) {
                    emptyList()
                }
            }.sortedBy { it.first }
            .takeLast(Constants.Compactor.STATUS_LOG_LINES)
            .map { it.second }
}
