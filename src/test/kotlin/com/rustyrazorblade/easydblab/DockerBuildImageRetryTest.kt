package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Image
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.io.PipedInputStream
import java.util.stream.Stream

/**
 * Which image build failures [Docker.buildImage] retries.
 *
 * Under Docker's containerd image store, an image prune that runs while a build pulls its base
 * deletes the build's content leases, and the build fails with containerd's "lease does not exist".
 * Nothing is wrong with the Dockerfile, and the same build run again succeeds. Any other build
 * failure, such as a `RUN` step that exits non-zero, fails the same way each time and is not retried.
 * The Docker daemon is an external system, so a scripted client stands in for it; the retry policy
 * is the production one with a 1 ms backoff.
 */
class DockerBuildImageRetryTest : BaseKoinTest() {
    /** Fails the first builds with the scripted errors, then succeeds; counts every attempt. */
    private class ScriptedBuildClient(
        failures: List<Exception>,
    ) : DockerClientInterface {
        private val pending = ArrayDeque(failures)
        var builds = 0

        override fun buildImage(
            contextDir: File,
            imageTag: String,
            onOutput: (String) -> Unit,
        ): String {
            builds++
            onOutput("attempt $builds")
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
        RetryConfig.from<Unit>(RetryUtil.createDockerBuildRetryConfig<Unit>()).intervalFunction { _ -> 1L }.build()

    private fun build(client: DockerClientInterface) =
        Docker(context, client, buildRetryConfig = fastRetry).buildImage("FROM scratch\n", "edl/test:1")

    @ParameterizedTest(name = "{0}")
    @MethodSource("buildFailures")
    fun `a build failure is retried only when the daemon lost the build's content lease`(
        description: String,
        failure: Exception,
        expectedAttempts: Int,
    ) {
        val client = ScriptedBuildClient(List(Constants.Retry.MAX_DOCKER_RETRIES) { failure })

        assertThatThrownBy { build(client) }
            .`as`(description)
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("Error building image edl/test:1")
        assertThat(client.builds).`as`(description).isEqualTo(expectedAttempts)
    }

    @Test
    fun `a build whose content lease was pruned once succeeds on the next attempt`() {
        val client = ScriptedBuildClient(listOf(leaseLost()))

        build(client)

        assertThat(client.builds).isEqualTo(2)
    }

    @Test
    fun `a build that fails on every attempt reports the last attempt's output`() {
        val client = ScriptedBuildClient(List(Constants.Retry.MAX_DOCKER_RETRIES) { leaseLost() })

        assertThatThrownBy { build(client) }
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("attempt ${Constants.Retry.MAX_DOCKER_RETRIES}")
            .hasMessageNotContaining("attempt 1")
            .hasRootCauseMessage(leaseLost().message)
    }

    companion object {
        /** What docker-java raises when containerd's lease for a build was deleted under it (Docker 29). */
        fun leaseLost() =
            DockerClientException(
                "Could not build image: failed to prepare extraction snapshot \"extract-213246946-4M0G " +
                    "sha256:4ca24ca3135d3d6184f0e0f38d22f74f3d542976284210d90d710b2efa0342c6\": " +
                    "NotFound: lease does not exist: not found",
            )

        @JvmStatic
        fun buildFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of("the content lease was pruned", leaseLost(), Constants.Retry.MAX_DOCKER_RETRIES),
                Arguments.of(
                    "a RUN step exited non-zero",
                    DockerClientException(
                        "Could not build image: The command '/bin/sh -c apk add nope' returned a non-zero code: 1",
                    ),
                    1,
                ),
                Arguments.of(
                    "the base image does not exist",
                    DockerClientException("Could not build image: pull access denied for nope, repository does not exist"),
                    1,
                ),
                Arguments.of(
                    "an unrelated missing object",
                    DockerClientException("Could not build image: NotFound: content digest sha256:abc: not found"),
                    1,
                ),
            )
    }
}
