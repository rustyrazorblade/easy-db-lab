package com.rustyrazorblade.easydblab.docker

import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.exception.NotFoundException
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.DockerClientInterface
import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.Duration

/**
 * Which Docker API failures [ContainerExecutor] retries. The Docker daemon is an external system,
 * so its client is mocked; the retry policy is the production one with a 1ms backoff.
 *
 * docker-java's HTTP transport does not throw a socket error as an IOException: it wraps it in a
 * RuntimeException. Those are the failures the policy's IOException rule exists for.
 */
class ContainerExecutorRetryTest : BaseKoinTest() {
    private val client = mock<DockerClientInterface>()
    private val fastRetry: RetryConfig =
        RetryConfig.from<Unit>(RetryUtil.createDockerRetryConfig<Unit>()).intervalFunction { _ -> 1L }.build()

    private val events = mutableListOf<Event>()

    @BeforeEach
    fun captureEvents() {
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
    }

    private fun executor() = ContainerExecutor(client, pollInterval = Duration.ofMillis(1), retryConfig = fastRetry)

    /** A 404 means the container does not exist, which no retry fixes: the start fails at once. */
    @Test
    fun `a start of a container that does not exist fails at once`() {
        var starts = 0
        doAnswer {
            starts++
            throw NotFoundException("No such container: c1")
        }.whenever(client).startContainer("c1")

        assertThatThrownBy { executor().startAndWaitForCompletion("c1") }
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("c1")
        assertThat(starts).isEqualTo(1)
        // docker-java prefixes the status: "Status 404: No such container: c1".
        assertThat(events.filterIsInstance<Event.Docker.ContainerStartError>().map { it.error })
            .singleElement()
            .asString()
            .contains("No such container: c1")
    }

    /** Removing a container that is already gone has nothing left to do, so it succeeds quietly. */
    @Test
    fun `removing a container that is already gone succeeds without a retry or an error`() {
        var removes = 0
        doAnswer {
            removes++
            throw NotFoundException("No such container: c1")
        }.whenever(client).removeContainer("c1", true)

        executor().removeContainer("c1")

        assertThat(removes).isEqualTo(1)
        assertThat(events.filterIsInstance<Event.Docker.ContainerRemoveError>()).isEmpty()
    }

    @Test
    fun `a start that fails on a dropped Docker socket is retried and then waits for the container`() {
        var starts = 0
        doAnswer {
            starts++
            if (starts < 3) throw RuntimeException(IOException("Broken pipe"))
        }.whenever(client).startContainer("c1")
        val inspect = mock<InspectContainerResponse>()
        val state = mock<InspectContainerResponse.ContainerState>()
        whenever(state.running).thenReturn(false)
        whenever(inspect.state).thenReturn(state)
        whenever(client.inspectContainer(any())).thenReturn(inspect)

        assertThat(executor().startAndWaitForCompletion("c1")).isSameAs(state)
        assertThat(starts).isEqualTo(3)
    }

    @Test
    fun `a start that fails for a reason other than I-O fails at once`() {
        var starts = 0
        doAnswer {
            starts++
            throw IllegalArgumentException("invalid container id")
        }.whenever(client).startContainer("c1")

        assertThatThrownBy { executor().startAndWaitForCompletion("c1") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun `a socket error that outlasts every retry is reported as a Docker start failure`() {
        doAnswer { throw RuntimeException(IOException("Connection refused")) }.whenever(client).startContainer("c1")

        assertThatThrownBy { executor().startAndWaitForCompletion("c1") }
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("c1")
    }

    @Test
    fun `a remove that fails on a dropped Docker socket is retried`() {
        var removes = 0
        doAnswer {
            removes++
            if (removes < 2) throw RuntimeException(IOException("Broken pipe"))
        }.whenever(client).removeContainer("c1", true)

        executor().removeContainer("c1")

        assertThat(removes).isEqualTo(2)
    }
}
