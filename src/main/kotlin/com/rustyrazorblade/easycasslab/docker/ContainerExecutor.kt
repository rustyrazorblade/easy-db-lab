package com.rustyrazorblade.easycasslab.docker

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model.Frame
import com.rustyrazorblade.easycasslab.DockerClientInterface
import com.rustyrazorblade.easycasslab.DockerException
import com.rustyrazorblade.easycasslab.output.OutputHandler
import com.rustyrazorblade.easycasslab.providers.aws.RetryUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration
import kotlin.concurrent.thread

/**
 * Manages the execution lifecycle of a Docker container.
 */
class ContainerExecutor(
    private val dockerClient: DockerClientInterface,
) : KoinComponent {
    private val outputHandler: OutputHandler by inject()

    companion object {
        private val log = KotlinLogging.logger {}
        private val CONTAINER_POLLING_INTERVAL = Duration.ofSeconds(1)
        private val DEFAULT_MAX_WAIT_TIME = Duration.ofMinutes(10)
    }

    /**
     * Start a container and wait for it to complete.
     *
     * Uses retry logic for transient Docker API failures.
     *
     * @param containerId The ID of the container to start
     * @param maxWaitTime Maximum time to wait (default: 10 minutes)
     * @return The container state after completion
     */
    fun startAndWaitForCompletion(
        containerId: String,
        maxWaitTime: Duration = DEFAULT_MAX_WAIT_TIME,
    ): InspectContainerResponse.ContainerState {
        val retryConfig = RetryUtil.createDockerRetryConfig<Unit>()
        val retry = Retry.of("docker-start-$containerId", retryConfig)

        try {
            Retry
                .decorateRunnable(retry) {
                    dockerClient.startContainer(containerId)
                }.run()
            outputHandler.handleMessage("Starting container $containerId")
        } catch (e: com.github.dockerjava.api.exception.DockerException) {
            val errorMsg = "Docker error starting container: $containerId"
            log.error(e) { errorMsg }
            outputHandler.handleError("Error starting container: ${e.message}", e)
            throw DockerException("Failed to start container $containerId", e)
        } catch (e: IOException) {
            val errorMsg = "IO error starting container: $containerId"
            log.error(e) { errorMsg }
            outputHandler.handleError("Error starting container: ${e.message}", e)
            throw DockerException("IO error starting container $containerId", e)
        }

        return waitForContainerToComplete(containerId, maxWaitTime)
    }

    /**
     * Wait for a container to complete execution.
     *
     * @param containerId The ID of the container to monitor
     * @param maxWaitTime Maximum time to wait (default: 10 minutes)
     * @return The final container state
     * @throws IllegalStateException if timeout is reached
     */
    private fun waitForContainerToComplete(
        containerId: String,
        maxWaitTime: Duration = DEFAULT_MAX_WAIT_TIME,
    ): InspectContainerResponse.ContainerState {
        val startTime = System.currentTimeMillis()
        val maxWaitTimeMs = maxWaitTime.toMillis()
        var containerState: InspectContainerResponse.ContainerState

        do {
            if (System.currentTimeMillis() - startTime > maxWaitTimeMs) {
                error("Container $containerId did not complete within $maxWaitTime")
            }

            Thread.sleep(CONTAINER_POLLING_INTERVAL.toMillis())
            containerState = dockerClient.inspectContainer(containerId).state
        } while (containerState.running == true)

        return containerState
    }

    /**
     * Remove a container and its volumes.
     *
     * Uses retry logic for transient Docker API failures.
     *
     * @param containerId The ID of the container to remove
     */
    @Suppress("TooGenericExceptionCaught")
    fun removeContainer(containerId: String) {
        val retryConfig = RetryUtil.createDockerRetryConfig<Unit>()
        val retry = Retry.of("docker-remove-$containerId", retryConfig)

        try {
            Retry
                .decorateRunnable(retry) {
                    dockerClient.removeContainer(containerId, true)
                }.run()
        } catch (e: DockerException) {
            log.error(e) { "Docker error while removing container $containerId" }
            outputHandler.handleError("Failed to remove container: ${e.message}", e)
        } catch (e: RuntimeException) {
            log.error(e) { "Runtime error while removing container $containerId" }
            outputHandler.handleError("Failed to remove container: ${e.message}", e)
        }
    }
}

/**
 * Manages input/output streams for a Docker container.
 */
class ContainerIOManager(
    private val dockerClient: DockerClientInterface,
) : KoinComponent {
    private val outputHandler: OutputHandler by inject()

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private var inputPipe: PipedOutputStream? = null
    private var inputThread: Thread? = null

    /**
     * Attach to a container and set up IO streams.
     *
     * @param containerId The ID of the container to attach to
     * @param enableStdin Whether to enable stdin redirection
     * @return The number of frames read (for tracking purposes)
     */
    fun attachToContainer(
        containerId: String,
        enableStdin: Boolean = true,
    ): Int {
        var framesRead = 0
        require(containerId.isNotBlank()) { "Container ID cannot be blank" }

        if (enableStdin) {
            setupStdinRedirection()
        }

        outputHandler.handleMessage("Attaching to running container")

        val frameCallback =
            object : ResultCallback.Adapter<Frame>() {
                override fun onNext(item: Frame?) {
                    if (item == null) return

                    framesRead++
                    outputHandler.handleFrame(item)
                }

                override fun onError(throwable: Throwable) {
                    log.error(throwable) { "Container attachment error" }
                    outputHandler.handleError("Container attachment error", throwable)
                    super.onError(throwable)
                }
            }

        val stdinStream = inputPipe?.let { PipedInputStream(it) }
        dockerClient.attachContainer(containerId, stdinStream ?: PipedInputStream(), frameCallback)

        return framesRead
    }

    /**
     * Set up stdin redirection from the host to the container.
     */
    private fun setupStdinRedirection() {
        inputPipe = PipedOutputStream()
        val stdIn = System.`in`.bufferedReader()

        inputThread =
            thread(isDaemon = true, name = "Docker-stdin-redirect") {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val line = stdIn.readLine() ?: break
                        inputPipe?.write((line + "\n").toByteArray())
                    }
                } catch (ignored: IOException) {
                    log.debug { "Stdin redirection stopped: ${ignored.message}" }
                } catch (ignored: InterruptedException) {
                    log.debug { "Stdin redirection interrupted" }
                }
            }
    }

    /**
     * Close IO streams and clean up resources.
     */
    fun close() {
        try {
            inputPipe?.close()
            inputThread?.interrupt()
            outputHandler.close()
        } catch (e: IOException) {
            log.error(e) { "IO error while closing IO manager" }
        } catch (e: InterruptedException) {
            log.error(e) { "Thread interrupted while closing IO manager" }
            Thread.currentThread().interrupt()
        }
    }
}

/**
 * Monitors and reports on container state.
 */
class ContainerStateMonitor : KoinComponent {
    private val outputHandler: OutputHandler by inject()

    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Build a return message based on container state.
     *
     * @param containerState The final container state
     * @param framesRead The number of frames read from the container
     * @return A formatted message describing the container exit status
     */
    fun buildReturnMessage(
        containerState: InspectContainerResponse.ContainerState,
        framesRead: Int,
    ): String {
        val errorMessage = containerState.error?.let { ", $it" } ?: ""
        val exitCode = containerState.exitCodeLong ?: -1L

        return "Container exited with exit code $exitCode$errorMessage, frames read: $framesRead"
    }

    /**
     * Report the final container state.
     *
     * @param containerState The final container state
     * @param framesRead The number of frames read
     */
    fun reportFinalState(
        containerState: InspectContainerResponse.ContainerState,
        framesRead: Int,
    ) {
        val message = buildReturnMessage(containerState, framesRead)
        outputHandler.handleMessage(message)
        log.info { message }
    }
}
