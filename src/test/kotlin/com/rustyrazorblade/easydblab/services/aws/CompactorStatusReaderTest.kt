package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.OutputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.Container
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse
import software.amazon.awssdk.services.ecs.model.DesiredStatus
import software.amazon.awssdk.services.ecs.model.ListTasksRequest
import software.amazon.awssdk.services.ecs.model.ListTasksResponse
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.ServiceEvent
import software.amazon.awssdk.services.ecs.model.Task
import software.amazon.awssdk.services.ecs.model.TaskStopCode
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse
import java.time.Instant

/**
 * What `observability compactor status` reads: which task it reports, why that task stopped, and
 * the task's newest log lines from every container, in time order. ECS and CloudWatch Logs are
 * faked at their client boundary; the log stream names come from the real task definition.
 */
class CompactorStatusReaderTest {
    private val region = "eu-west-1"
    private val ecs = mock<EcsClient>()
    private val logs = mock<CloudWatchLogsClient>()
    private val regionalClients =
        object : RegionalClients {
            override fun ec2(region: String) = error("not used")

            override fun ecs(region: String) = ecs

            override fun logs(region: String) = logs
        }
    private val reader = CompactorStatusReader(regionalClients)

    /** Each task by the desired status `ListTasks` is asked for. */
    private val tasks = mutableMapOf<DesiredStatus, List<Task>>()

    /** Each log stream's events; a stream that is not here does not exist. */
    private val streams = mutableMapOf<String, List<OutputLogEvent>>()
    private val requestedStreams = mutableListOf<String>()

    @BeforeEach
    fun fakes() {
        serviceIs(service(desired = 1, running = 1))
        whenever(ecs.listTasks(any<ListTasksRequest>())).thenAnswer { invocation ->
            val status = invocation.getArgument<ListTasksRequest>(0).desiredStatus()
            ListTasksResponse
                .builder()
                .taskArns(tasks[status].orEmpty().map { it.taskArn() })
                .build()
        }
        whenever(ecs.describeTasks(any<DescribeTasksRequest>())).thenAnswer { invocation ->
            val arns = invocation.getArgument<DescribeTasksRequest>(0).tasks()
            DescribeTasksResponse
                .builder()
                .tasks(tasks.values.flatten().filter { it.taskArn() in arns })
                .build()
        }
        whenever(logs.getLogEvents(any<GetLogEventsRequest>())).thenAnswer { invocation ->
            val stream = invocation.getArgument<GetLogEventsRequest>(0).logStreamName()
            requestedStreams += stream
            val events = streams[stream] ?: throw ResourceNotFoundException.builder().message("no stream $stream").build()
            GetLogEventsResponse.builder().events(events).build()
        }
    }

    private fun serviceIs(vararg services: Service) {
        whenever(ecs.describeServices(any<DescribeServicesRequest>()))
            .thenReturn(DescribeServicesResponse.builder().services(*services).build())
    }

    private fun service(
        desired: Int,
        running: Int,
        pending: Int = 0,
        status: String = "ACTIVE",
        events: List<ServiceEvent> = emptyList(),
    ) = Service
        .builder()
        .serviceName(Constants.Compactor.SERVICE)
        .status(status)
        .desiredCount(desired)
        .runningCount(running)
        .pendingCount(pending)
        .events(events)
        .build()

    private fun task(
        id: String,
        createdAt: Long,
        lastStatus: String,
        configure: Task.Builder.() -> Unit = {},
    ): Task =
        Task
            .builder()
            .taskArn("arn:aws:ecs:$region:1:task/easy-db-lab/$id")
            .createdAt(Instant.ofEpochSecond(createdAt))
            .lastStatus(lastStatus)
            .apply(configure)
            .build()

    private fun event(
        at: Long,
        message: String,
    ) = OutputLogEvent
        .builder()
        .timestamp(at)
        .message(message)
        .build()

    /** The stream awslogs writes [container]'s output to, from the real task definition's options. */
    private fun streamOf(
        container: String,
        taskId: String,
    ): String {
        val definition = CompactorTaskDefinition("easy-db-lab-acct", region, "arn:task", "arn:exec").containers()
        val prefix =
            definition
                .single { it.name() == container }
                .logConfiguration()
                .options()
                .getValue("awslogs-stream-prefix")
        return "$prefix/$container/$taskId"
    }

    @Test
    fun `with no running task, the newer of two stopped tasks is reported with why it stopped`() {
        tasks[DesiredStatus.STOPPED] =
            listOf(
                task("older", createdAt = 100, lastStatus = "STOPPED"),
                task("newer", createdAt = 200, lastStatus = "STOPPED") {
                    stoppedReason("Essential container in task exited")
                    stopCode(TaskStopCode.ESSENTIAL_CONTAINER_EXITED)
                    containers(
                        Container
                            .builder()
                            .name(CompactorTaskDefinition.LOKI_CONTAINER)
                            .lastStatus("STOPPED")
                            .exitCode(1)
                            .reason("CannotPullContainerError")
                            .build(),
                    )
                },
            )
        serviceIs(service(desired = 1, running = 0))

        val status = reader.read(region)

        assertThat(status.taskId).isEqualTo("newer")
        assertThat(status.state).isEqualTo("failing")
        assertThat(status.stoppedReason).isEqualTo("Essential container in task exited")
        assertThat(status.stopCode).isEqualTo("EssentialContainerExited")
        assertThat(status.containers)
            .containsExactly(CompactorContainerState(CompactorTaskDefinition.LOKI_CONTAINER, "STOPPED", 1, "CannotPullContainerError"))
    }

    @Test
    fun `log lines from every container are merged in time order, prefixed, capped, and a missing stream is skipped`() {
        tasks[DesiredStatus.RUNNING] = listOf(task("t1", createdAt = 100, lastStatus = "RUNNING"))
        // Interleaved lines from two containers; the Loki compactor never wrote a stream.
        streams[streamOf(CompactorTaskDefinition.MIMIR_CONTAINER, "t1")] =
            (0 until Constants.Compactor.STATUS_LOG_LINES).map { event(at = 2L * it, message = "mimir $it") }
        streams[streamOf(CompactorTaskDefinition.TEMPO_WORKER_CONTAINER, "t1")] =
            (0 until Constants.Compactor.STATUS_LOG_LINES).map { event(at = 2L * it + 1, message = "tempo $it") }

        val lines = reader.read(region).logLines

        assertThat(lines).hasSize(Constants.Compactor.STATUS_LOG_LINES)
        val half = Constants.Compactor.STATUS_LOG_LINES / 2
        assertThat(lines.first()).isEqualTo("[${CompactorTaskDefinition.MIMIR_CONTAINER}] mimir $half")
        assertThat(lines[1]).isEqualTo("[${CompactorTaskDefinition.TEMPO_WORKER_CONTAINER}] tempo $half")
        assertThat(lines.last())
            .isEqualTo("[${CompactorTaskDefinition.TEMPO_WORKER_CONTAINER}] tempo ${Constants.Compactor.STATUS_LOG_LINES - 1}")
        assertThat(requestedStreams).containsExactlyInAnyOrderElementsOf(CompactorTaskDefinition.CONTAINERS.map { streamOf(it, "t1") })
    }

    /** An idle Tempo worker logs that it found no job on every poll; that is its normal state, not an error. */
    @Test
    fun `the Tempo worker's idle no-jobs line is left out, and every other line is kept`() {
        tasks[DesiredStatus.RUNNING] = listOf(task("t1", createdAt = 100, lastStatus = "RUNNING"))
        val idle =
            "level=error ts=2026-09-27T10:00:00Z caller=worker.go:1 msg=\"error calling scheduler\" " +
                "err=\"rpc error: code = NotFound desc = no jobs found\""
        val otherSchedulerError = "level=error msg=\"error calling scheduler\" err=\"rpc error: code = Unavailable\""
        streams[streamOf(CompactorTaskDefinition.TEMPO_WORKER_CONTAINER, "t1")] =
            listOf(
                event(at = 1, message = idle),
                event(at = 2, message = otherSchedulerError),
                event(at = 3, message = idle),
                event(at = 4, message = "level=info msg=\"compacted block\""),
            )

        val lines = reader.read(region).logLines

        val worker = CompactorTaskDefinition.TEMPO_WORKER_CONTAINER
        assertThat(lines).containsExactly("[$worker] $otherSchedulerError", "[$worker] level=info msg=\"compacted block\"")
    }

    @Test
    fun `an inactive or missing service does not exist`() {
        serviceIs(service(desired = 0, running = 0, status = "INACTIVE"))
        assertThat(reader.read(region).exists).isFalse()

        serviceIs()
        assertThat(reader.read(region).exists).isFalse()
    }

    @Test
    fun `the newest service events are reported oldest first`() {
        val events =
            (1..Constants.Compactor.STATUS_SERVICE_EVENTS + 2).map {
                // ECS lists the newest first.
                ServiceEvent
                    .builder()
                    .createdAt(Instant.ofEpochSecond(1000L - it))
                    .message("event $it")
                    .build()
            }
        serviceIs(service(desired = 1, running = 0, events = events))

        val reported = reader.read(region).serviceEvents

        assertThat(reported).hasSize(Constants.Compactor.STATUS_SERVICE_EVENTS)
        assertThat(reported.first()).endsWith("event ${Constants.Compactor.STATUS_SERVICE_EVENTS}")
        assertThat(reported.last()).endsWith("event 1")
    }

    @Test
    fun `status changes nothing`() {
        tasks[DesiredStatus.RUNNING] = listOf(task("t1", createdAt = 100, lastStatus = "RUNNING"))
        val s3 =
            mock<S3Client>().also {
                whenever(it.getBucketLocation(any<GetBucketLocationRequest>()))
                    .thenReturn(GetBucketLocationResponse.builder().locationConstraint(region).build())
            }
        val compactor = DefaultCompactorService(s3, mock(), mock(), mock(), regionalClients, EventBus())

        val status = compactor.status("easy-db-lab-acct")

        assertThat(status.state).isEqualTo("running")
        verify(ecs, never()).createService(any<CreateServiceRequest>())
        verify(ecs, never()).updateService(any<UpdateServiceRequest>())
        verify(ecs, never()).registerTaskDefinition(any<RegisterTaskDefinitionRequest>())
    }

    @Test
    fun `the service's pending task count is reported`() {
        tasks[DesiredStatus.RUNNING] = listOf(task("t1", createdAt = 100, lastStatus = "PROVISIONING"))
        serviceIs(service(desired = 1, running = 0, pending = 1))

        val status = reader.read(region)

        assertThat(status.pendingCount).isEqualTo(1)
        assertThat(status.state).isEqualTo("starting")
    }

    @Test
    fun `a service with no task placed yet reads as starting`() {
        serviceIs(service(desired = 1, running = 0))

        val status = reader.read(region)

        assertThat(status.taskId).isEmpty()
        assertThat(status.state).isEqualTo("starting")
    }

    @Test
    fun `a service that asks for a task reads as starting until a task stops with none pending, then failing`() {
        assertThat(CompactorStatus(region, exists = true, desiredCount = 1, pendingCount = 1, taskStatus = "PROVISIONING").state)
            .isEqualTo("starting")
        // A pending replacement after a crash is still starting.
        assertThat(CompactorStatus(region, exists = true, desiredCount = 1, pendingCount = 1, taskStatus = "STOPPED").state)
            .isEqualTo("starting")
        assertThat(CompactorStatus(region, exists = true, desiredCount = 1, pendingCount = 0, taskStatus = "STOPPED").state)
            .isEqualTo("failing")
        // No task has stopped yet: ECS has not placed the first one.
        assertThat(CompactorStatus(region, exists = true, desiredCount = 1, pendingCount = 0).state).isEqualTo("starting")
        assertThat(CompactorStatus(region, exists = true, desiredCount = 1, taskStatus = "PROVISIONING").state).isEqualTo("starting")
        assertThat(CompactorStatus(region, exists = true, desiredCount = 1, runningCount = 1, pendingCount = 1).state)
            .isEqualTo("running")
        assertThat(CompactorStatus(region, exists = true, desiredCount = 0, runningCount = 1).state).isEqualTo("stopping")
        assertThat(CompactorStatus(region, exists = true, desiredCount = 0, runningCount = 0).state).isEqualTo("stopped")
        assertThat(CompactorStatus(region, exists = false).state).isEqualTo("not created")
    }
}
