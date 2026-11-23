package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.model.Container
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.docker.DefaultDockerClientProvider
import com.rustyrazorblade.easydblab.runCatchingException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.testcontainers.DockerClientFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
        const val POLL_MILLIS = 200L
    }

    override fun coreTestModules(): List<Module> = emptyList()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { EventBus() }
                single<OutputHandler> { BufferedOutputHandler() }
            },
        )

    private val daemon by lazy { DockerClientFactory.instance().client() }

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
        val seenRunning = AtomicBoolean(false)
        val watcherFailure = AtomicReference<Throwable>()
        val watcher =
            thread(isDaemon = true) {
                runCatchingException {
                    while (!seenRunning.get() && !Thread.currentThread().isInterrupted) {
                        if (containersRunning(marker).any { it.state == "running" }) seenRunning.set(true)
                        Thread.sleep(POLL_MILLIS)
                    }
                }.onFailure { e ->
                    // An interrupt means the wait ended, and it can surface wrapped (an InterruptedIOException
                    // cause); only a failure while still watching is the watcher's own.
                    if (e !is InterruptedException && !Thread.currentThread().isInterrupted) watcherFailure.set(e)
                }
            }

        assertThatThrownBy {
            docker.runContainer(IMAGE, mutableListOf("sh", "-c", "sleep $SLEEP_SECONDS # $marker"), "", Duration.ofSeconds(BOUND_SECONDS))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("did not complete")
        watcher.interrupt()
        watcher.join()

        assertThat(watcherFailure.get()).`as`("the watcher could not list containers").isNull()
        assertThat(seenRunning).`as`("the container was running when its wait ended").isTrue()
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
