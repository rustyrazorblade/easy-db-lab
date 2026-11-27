package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.EventType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.docker.DefaultDockerClientProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.testcontainers.DockerClientFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.github.dockerjava.api.model.Event as DockerEvent

/**
 * Runs a real container through [Docker.runContainer] on the local daemon: a container still
 * running when its wait ends must be stopped and removed, not left behind (a Packer container past
 * its bound would otherwise keep building), and one that finishes is removed too.
 */
class DockerContainerBoundIntegrationTest : BaseKoinTest() {
    private companion object {
        const val IMAGE = "busybox:1.36"
        const val SLEEP_SECONDS = 60
        const val BOUND_SECONDS = 5L
        const val KILLED_EXIT_CODE = 137
        const val EVENTS_SECONDS = 30L
        const val SINCE_MARGIN_SECONDS = 5L
        val LIFECYCLE_ACTIONS = arrayOf("start", "kill", "die", "destroy")
    }

    override fun coreTestModules(): List<Module> = emptyList()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    EventBus().apply {
                        addListener(
                            object : EventListener {
                                override fun onEvent(envelope: EventEnvelope) {
                                    (envelope.event as? Event.Docker.ContainerStarting)?.let { startedIds += it.containerId }
                                }

                                override fun close() = Unit
                            },
                        )
                    }
                }
                single<OutputHandler> { BufferedOutputHandler() }
            },
        )

    private val daemon by lazy { DockerClientFactory.instance().client() }

    private val startedIds = CopyOnWriteArrayList<String>()

    /** The id of the one container [Docker.runContainer] started, as its start event reported it. */
    private fun startedContainerId(): String {
        assertThat(startedIds).`as`("the container start was reported once").hasSize(1)
        return startedIds.single()
    }

    /**
     * Records the container actions the daemon reports while [block] runs, keyed by container id,
     * with a die carrying its exit code (`die:137`). The stream asks the daemon to replay from
     * [SINCE_MARGIN_SECONDS] before the call, so a `start` the daemon emits before it has registered
     * this subscriber is replayed, not lost; the stream being open does not prove the subscriber is
     * registered. The margin also absorbs the rounding of the timestamp to whole seconds. The replay
     * can include other tests' containers, which is harmless: callers read only the container id
     * [Docker.runContainer] reported, and only [LIFECYCLE_ACTIONS] are streamed, so the other
     * tests' execs stay out.
     */
    private fun recordingContainerEvents(block: () -> Unit): ContainerEvents {
        val events = ContainerEvents()
        val since = Instant.now().epochSecond - SINCE_MARGIN_SECONDS
        daemon
            .eventsCmd()
            .withEventTypeFilter(EventType.CONTAINER)
            .withEventFilter(*LIFECYCLE_ACTIONS)
            .withSince(since.toString())
            .exec(events)
            .use {
                assertThat(events.awaitStarted(EVENTS_SECONDS, TimeUnit.SECONDS))
                    .`as`("the daemon opened the event stream")
                    .isTrue()
                block()
                events.awaitDestroyOf(startedContainerId())
            }
        return events
    }

    /** Container actions in the order the daemon reported them, per container id. */
    private class ContainerEvents : ResultCallback.Adapter<DockerEvent>() {
        private val actions = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        private val destroyed = ConcurrentHashMap<String, CountDownLatch>()

        override fun onNext(event: DockerEvent) {
            val id = event.actor?.id ?: event.id ?: return
            val action = event.action.orEmpty()
            actions.computeIfAbsent(id) { CopyOnWriteArrayList() } +=
                when (action) {
                    "die" -> "die:${event.actor?.attributes?.get("exitCode").orEmpty()}"
                    else -> action
                }
            if (action == "destroy") latchFor(id).countDown()
        }

        fun of(containerId: String): List<String> = actions[containerId].orEmpty().toList()

        fun awaitDestroyOf(containerId: String) {
            assertThat(latchFor(containerId).await(EVENTS_SECONDS, TimeUnit.SECONDS))
                .`as`("the daemon reported the destroy of container $containerId within $EVENTS_SECONDS seconds")
                .isTrue()
        }

        private fun latchFor(containerId: String) = destroyed.computeIfAbsent(containerId) { CountDownLatch(1) }
    }

    /** The containers, running or stopped, whose command carries [marker]. */
    private fun containersRunning(marker: String): List<Container> =
        daemon
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .filter { it.command.contains(marker) }

    private fun docker(): Docker {
        if (daemon
                .listImagesCmd()
                .withReferenceFilter(IMAGE)
                .exec()
                .isEmpty()
        ) {
            daemon.pullImageCmd(IMAGE).start().awaitCompletion()
        }
        return Docker(mock<Context>(), DefaultDockerClientProvider().getDockerClient())
    }

    @Test
    fun `a container still running when its wait ends is gone afterwards`() {
        val marker = "edl-${UUID.randomUUID().toString().take(8)}"
        val docker = docker()

        val events =
            recordingContainerEvents {
                assertThatThrownBy {
                    docker.runContainer(
                        IMAGE,
                        mutableListOf("sh", "-c", "sleep $SLEEP_SECONDS # $marker"),
                        "",
                        Duration.ofSeconds(BOUND_SECONDS),
                    )
                }.isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("did not complete")
            }

        // The daemon's own record, not a sample of its state: it started, was killed while running
        // (a sleep that exits on its own would die with 0 and no kill before it), and was destroyed.
        assertThat(events.of(startedContainerId()))
            .containsSubsequence("start", "kill", "die:$KILLED_EXIT_CODE", "destroy")
        assertThat(containersRunning(marker)).isEmpty()
    }

    @Test
    fun `a container that finishes is removed`() {
        val marker = "edl-${UUID.randomUUID().toString().take(8)}"

        val result = docker().runContainer(IMAGE, mutableListOf("echo", marker), "", Duration.ofMinutes(1))

        assertThat(result.isSuccess).isTrue()
        assertThat(containersRunning(marker)).isEmpty()
    }
}
