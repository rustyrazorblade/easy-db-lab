package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.InternalServerErrorException
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Image
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.docker.DockerBuildRetry
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.io.PipedInputStream
import java.util.stream.Stream

/**
 * Which image build failures [Docker.buildImage] retries, and what a retried build leaves behind.
 *
 * Under Docker's containerd image store, an image prune that runs while a build pulls its base
 * deletes the build's content leases, and the build fails with containerd's "lease does not exist".
 * Nothing is wrong with the Dockerfile, and the same build run again succeeds. Any other build
 * failure, such as a `RUN` step that exits non-zero, fails the same way each time and is not retried.
 * The Docker daemon is an external system, so a scripted client stands in for it; the retry policy
 * is the production one with a 1 ms backoff.
 */
class DockerBuildImageRetryTest : BaseKoinTest() {
    /**
     * Fails the first builds with the scripted errors, then succeeds. It counts every attempt and
     * records the Dockerfile each attempt found in its build context.
     */
    private class ScriptedBuildClient(
        failures: List<Exception>,
        private val output: String = "",
    ) : DockerClientInterface {
        private val pending = ArrayDeque(failures)
        val dockerfiles = mutableListOf<String?>()
        val builds get() = dockerfiles.size

        override fun buildImage(
            contextDir: File,
            imageTag: String,
            onOutput: (String) -> Unit,
        ): String {
            dockerfiles += File(contextDir, "Dockerfile").takeIf { it.isFile }?.readText()
            onOutput("attempt $builds")
            output.lines().filter { it.isNotEmpty() }.forEach(onOutput)
            pending.removeFirstOrNull()?.let { throw it }
            return "sha256:built"
        }

        override fun listImages(
            name: String,
            tag: String,
        ): List<Image> = error("not used")

        override fun pullImage(
            name: String,
            tag: String,
            callback: PullImageResultCallback,
        ) = error("not used")

        override fun createContainer(imageTag: String): ContainerCreationCommand = error("not used")

        override fun attachContainer(
            containerId: String,
            stdin: PipedInputStream,
            callback: ResultCallback.Adapter<Frame>,
        ) = error("not used")

        override fun startContainer(containerId: String) = error("not used")

        override fun inspectContainer(containerId: String): InspectContainerResponse = error("not used")

        override fun removeContainer(
            containerId: String,
            removeVolumes: Boolean,
        ) = error("not used")
    }

    private val fastRetry: RetryConfig =
        RetryConfig.from<Unit>(DockerBuildRetry.config<Unit>()).intervalFunction { _ -> 1L }.build()

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

    @AfterEach
    fun clearInterrupt() {
        // A test that interrupts the build leaves the flag set; it must not reach the next test.
        Thread.interrupted()
    }

    private fun build(client: DockerClientInterface) = Docker(context, client, buildRetryConfig = fastRetry).buildImage(DOCKERFILE, TAG)

    private fun builtEvents() = events.filterIsInstance<Event.Docker.ImageBuilt>()

    @ParameterizedTest(name = "{0}")
    @MethodSource("buildFailures")
    fun `a build failure is retried only when the daemon lost the build's content lease`(
        description: String,
        failure: Exception,
        output: String,
        expectedAttempts: Int,
    ) {
        val client = ScriptedBuildClient(List(Constants.Retry.MAX_DOCKER_RETRIES) { failure }, output)

        assertThatThrownBy { build(client) }
            .`as`(description)
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("Error building image $TAG")
        assertThat(client.builds).`as`(description).isEqualTo(expectedAttempts)
    }

    @Test
    fun `a build whose content lease was pruned once succeeds on the next attempt, with its Dockerfile`() {
        val client = ScriptedBuildClient(listOf(leaseLost()))

        val printed = captureStdout { build(client) }

        assertThat(client.dockerfiles).containsExactly(DOCKERFILE, DOCKERFILE)
        assertThat(builtEvents()).containsExactly(Event.Docker.ImageBuilt(TAG))
        assertThat(printed)
            .contains("Docker build of $TAG lost its content lease")
            .contains("retrying, attempt 2 of ${Constants.Retry.MAX_DOCKER_RETRIES}")
    }

    @Test
    fun `a build that fails on every attempt reports the last attempt and its output, and is never built`() {
        val client = ScriptedBuildClient(List(Constants.Retry.MAX_DOCKER_RETRIES) { leaseLost() })

        var failure: Throwable? = null
        val printed = captureStdout { failure = catchThrowable { build(client) } }

        assertThat(failure)
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("(attempt ${Constants.Retry.MAX_DOCKER_RETRIES})")
            .hasRootCauseMessage(leaseLost().message)
        assertThat(ATTEMPT_LINE.findAll(failure?.message.orEmpty()).map { it.value }.toList())
            .containsExactly("attempt ${Constants.Retry.MAX_DOCKER_RETRIES}")
        assertThat(builtEvents()).isEmpty()
        // One line per retry that ran; none after the last attempt, which no retry follows.
        assertThat(printed.lines().count { "lost its content lease" in it })
            .isEqualTo(Constants.Retry.MAX_DOCKER_RETRIES - 1)
    }

    @Test
    fun `a lost lease then a failing RUN step stops at the RUN step and reports its output`() {
        val client = ScriptedBuildClient(listOf(leaseLost(), runStepFailed()), output = "step output\n")

        val failure = catchThrowable { build(client) }

        assertThat(client.builds).isEqualTo(2)
        assertThat(failure)
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("(attempt 2)")
            .hasMessageContaining("step output")
            .hasRootCauseMessage(runStepFailed().message)
        assertThat(ATTEMPT_LINE.findAll(failure.message.orEmpty()).map { it.value }.toList())
            .containsExactly("attempt 2")
        assertThat(builtEvents()).isEmpty()
    }

    @Test
    fun `an interrupted build is not retried and leaves the thread interrupted`() {
        // docker-java's BuildImageResultCallback.awaitImageId wraps an interrupt this way and clears the flag.
        val client = ScriptedBuildClient(listOf(DockerClientException("", InterruptedException("build interrupted"))))

        assertThatThrownBy { build(client) }.isInstanceOf(DockerException::class.java)
        assertThat(client.builds).isEqualTo(1)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }

    companion object {
        private const val TAG = "edl/test:1"
        private const val DOCKERFILE = "FROM scratch\n"

        /** The line each scripted build attempt prints first, on a line of its own in the failure's output tail. */
        private val ATTEMPT_LINE = Regex("""(?m)^attempt \d+$""")

        /** What docker-java raises when containerd's lease for a build was deleted under it (Docker 29). */
        fun leaseLost() =
            DockerClientException(
                "Could not build image: failed to prepare extraction snapshot \"extract-213246946-4M0G " +
                    "sha256:4ca24ca3135d3d6184f0e0f38d22f74f3d542976284210d90d710b2efa0342c6\": " +
                    "NotFound: lease does not exist: not found",
            )

        fun runStepFailed() =
            DockerClientException("Could not build image: The command '/bin/sh -c apk add nope' returned a non-zero code: 1")

        @JvmStatic
        fun buildFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of("the content lease was pruned", leaseLost(), "", Constants.Retry.MAX_DOCKER_RETRIES),
                Arguments.of("a RUN step exited non-zero", runStepFailed(), "", 1),
                Arguments.of(
                    "the base image does not exist",
                    DockerClientException("Could not build image: pull access denied for nope, repository does not exist"),
                    "",
                    1,
                ),
                Arguments.of(
                    "an unrelated missing object",
                    DockerClientException("Could not build image: NotFound: content digest sha256:abc: not found"),
                    "",
                    1,
                ),
                // The failure report carries the build's output; a step that prints the phrase and
                // then fails is the step's failure, not a lost lease.
                Arguments.of(
                    "a RUN step printed a lease error and exited non-zero",
                    DockerClientException(
                        "Could not build image: The command '/bin/sh -c ./elect' returned a non-zero code: 1",
                    ),
                    "election failed: lease does not exist\n",
                    1,
                ),
                // docker-java's own DockerException branch (an API error status), not DockerClientException.
                Arguments.of(
                    "the daemon answered 500 with a lost lease",
                    InternalServerErrorException("Status 500: failed to prepare extraction snapshot: lease does not exist: not found"),
                    "",
                    Constants.Retry.MAX_DOCKER_RETRIES,
                ),
                Arguments.of(
                    "the daemon answered 500 for another reason",
                    InternalServerErrorException("Status 500: failed to solve: no space left on device"),
                    "",
                    1,
                ),
                // The thread was asked to stop; the phrase in the message must not make it run again.
                // docker-java's own interrupt shape has an empty message (see the interrupt test
                // below); this row holds the predicate's exclusion for any wrapper that names the phrase.
                Arguments.of(
                    "an interrupted build whose message names a lost lease",
                    DockerClientException(
                        "Could not build image: lease does not exist",
                        InterruptedException("build interrupted"),
                    ),
                    "",
                    1,
                ),
            )
    }
}
