package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.ClusterNotFoundException
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest
import software.amazon.awssdk.services.ecs.model.DesiredStatus
import software.amazon.awssdk.services.ecs.model.ListTasksRequest
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.Task

/**
 * One container of the compactor's task, as ECS last saw it.
 *
 * @property name the container name.
 * @property lastStatus its last status, such as `RUNNING` or `STOPPED`.
 * @property exitCode its exit code, or null while it has not exited.
 * @property reason why it stopped, when ECS says; empty otherwise.
 */
data class CompactorContainerState(
    val name: String,
    val lastStatus: String,
    val exitCode: Int?,
    val reason: String,
)

/**
 * What `observability compactor status` shows.
 *
 * @property region the account bucket's region, where the service runs.
 * @property exists whether the service exists (and is not inactive).
 * @property desiredCount the tasks the service asks for.
 * @property runningCount the tasks that run.
 * @property taskId the current task, or the last stopped one; empty when there is none.
 * @property taskStatus that task's last status.
 * @property stoppedReason why that task stopped, when it did.
 * @property stopCode ECS's stop code for that task, when it stopped.
 * @property containers that task's containers.
 * @property serviceEvents the service's newest events, oldest first.
 * @property logLines the newest log lines of that task's containers, oldest first.
 */
data class CompactorStatus(
    val region: String,
    val exists: Boolean,
    val desiredCount: Int = 0,
    val runningCount: Int = 0,
    val taskId: String = "",
    val taskStatus: String = "",
    val stoppedReason: String = "",
    val stopCode: String = "",
    val containers: List<CompactorContainerState> = emptyList(),
    val serviceEvents: List<String> = emptyList(),
    val logLines: List<String> = emptyList(),
) {
    /**
     * The state as the operator reads it, from the desired and running counts. A service that asks
     * for a task but runs none is `failing` when its latest task stopped and `starting` otherwise, so
     * a task that crashes in a loop never reads as running.
     */
    val state: String
        get() =
            when {
                !exists -> "not created"
                desiredCount == 0 && runningCount > 0 -> "stopping"
                desiredCount == 0 -> "stopped"
                runningCount > 0 -> "running"
                taskStatus == STOPPED -> "failing"
                else -> "starting"
            }

    private companion object {
        const val STOPPED = "STOPPED"
    }
}

/** The compactor service, or null when it does not exist or is inactive. */
internal fun EcsClient.describeCompactorService(): Service? =
    try {
        describeServices(
            DescribeServicesRequest
                .builder()
                .cluster(Constants.Compactor.ECS_CLUSTER)
                .services(Constants.Compactor.SERVICE)
                .build(),
        ).services()
            .firstOrNull { it.status() != "INACTIVE" }
    } catch (_: ClusterNotFoundException) {
        null
    }

/**
 * Reads the account compactor's state: the service's counts and newest events, its current or last
 * task and why that task stopped, and the task's newest log lines. It changes nothing.
 */
class CompactorStatusReader(
    private val regionalClients: RegionalClients,
) {
    /** The compactor's status in [region]. */
    fun read(region: String): CompactorStatus =
        regionalClients.ecs(region).use { ecs ->
            val service = ecs.describeCompactorService() ?: return@use CompactorStatus(region, exists = false)
            val task = latestTask(ecs)
            val taskId = task?.taskArn()?.substringAfterLast('/').orEmpty()
            CompactorStatus(
                region = region,
                exists = true,
                desiredCount = service.desiredCount(),
                runningCount = service.runningCount(),
                taskId = taskId,
                taskStatus = task?.lastStatus().orEmpty(),
                stoppedReason = task?.stoppedReason().orEmpty(),
                stopCode = task?.stopCodeAsString().orEmpty(),
                containers =
                    task?.containers().orEmpty().map {
                        CompactorContainerState(it.name(), it.lastStatus().orEmpty(), it.exitCode(), it.reason().orEmpty())
                    },
                // ECS lists the newest event first.
                serviceEvents =
                    service
                        .events()
                        .take(Constants.Compactor.STATUS_SERVICE_EVENTS)
                        .reversed()
                        .map { "${it.createdAt()} ${it.message()}" },
                logLines = if (taskId.isEmpty()) emptyList() else regionalClients.logs(region).use { recentLogLines(it, taskId) },
            )
        }

    /** The running task, or the most recently created stopped one. */
    private fun latestTask(ecs: EcsClient): Task? =
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
                try {
                    logs
                        .getLogEvents(
                            GetLogEventsRequest
                                .builder()
                                .logGroupName(Constants.Compactor.LOG_GROUP)
                                .logStreamName(CompactorTaskDefinition.logStream(container, taskId))
                                .limit(Constants.Compactor.STATUS_LOG_LINES)
                                .startFromHead(false)
                                .build(),
                        ).events()
                        .map { it.timestamp() to "[$container] ${it.message()}" }
                } catch (_: ResourceNotFoundException) {
                    // A container that never started has no stream.
                    emptyList()
                }
            }.sortedBy { it.first }
            .takeLast(Constants.Compactor.STATUS_LOG_LINES)
            .map { it.second }
}
