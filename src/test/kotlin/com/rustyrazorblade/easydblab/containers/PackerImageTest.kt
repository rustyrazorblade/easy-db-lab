package com.rustyrazorblade.easydblab.containers

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Image
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.ContainerCreationCommand
import com.rustyrazorblade.easydblab.Docker
import com.rustyrazorblade.easydblab.DockerClientInterface
import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.configuration.SshTransport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.File
import java.io.PipedInputStream

/**
 * Tests for [PackerImage]: which image Packer runs in per SSH transport, and when the
 * SSM-capable image is built. Uses the real [Docker] wrapper over a fake client that records
 * pulls and builds, so the build-context handling in [Docker.buildImage] runs too.
 */
class PackerImageTest : BaseKoinTest() {
    /** Records pulls and builds; an image exists once it has been built or was seeded present. */
    private class RecordingDockerClient(
        private val failBuildWith: List<String>? = null,
    ) : DockerClientInterface {
        val localImages = mutableSetOf<String>()
        val pulled = mutableListOf<String>()
        val builtDockerfiles = mutableMapOf<String, String>()

        override fun listImages(
            name: String,
            tag: String,
        ): List<Image> = if ("$name:$tag" in localImages) listOf(mock()) else emptyList()

        override fun pullImage(
            name: String,
            tag: String,
            callback: PullImageResultCallback,
        ) {
            pulled.add("$name:$tag")
            callback.onComplete()
        }

        override fun buildImage(
            contextDir: File,
            imageTag: String,
            onOutput: (String) -> Unit,
        ): String {
            failBuildWith?.let { lines ->
                lines.forEach(onOutput)
                throw DockerClientException("Could not build image")
            }
            builtDockerfiles[imageTag] = File(contextDir, "Dockerfile").readText()
            localImages.add(imageTag)
            return "sha256:built"
        }

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

    private fun docker(client: DockerClientInterface) = Docker(context, client)

    @Test
    fun `direct runs the stock Packer image and never builds one`() {
        val client = RecordingDockerClient()

        val image = PackerImage(docker(client)).ensure(SshTransport.Direct)

        assertThat(image).isEqualTo("hashicorp/packer:full")
        assertThat(client.pulled).containsExactly("hashicorp/packer:full")
        assertThat(client.builtDockerfiles).isEmpty()
    }

    @Test
    fun `ssm builds the derived image from the packaged Dockerfile when it is missing`() {
        val client = RecordingDockerClient()
        val packerImage = PackerImage(docker(client))

        val image = packerImage.ensure(SshTransport.Ssm)

        assertThat(image).isEqualTo(packerImage.ssmImageTag)
        assertThat(client.builtDockerfiles.getValue(image))
            .contains("FROM hashicorp/packer:full")
            .contains("session-manager-plugin --version")
    }

    @Test
    fun `ssm reuses the derived image once it exists`() {
        val client = RecordingDockerClient()
        val packerImage = PackerImage(docker(client))

        packerImage.ensure(SshTransport.Ssm)
        packerImage.ensure(SshTransport.Ssm)

        assertThat(client.builtDockerfiles).hasSize(1)
    }

    @Test
    fun `the derived image tag follows the Dockerfile content`() {
        val client = RecordingDockerClient()

        val first = PackerImage(docker(client), "FROM hashicorp/packer:full\n").ssmImageTag
        val same = PackerImage(docker(client), "FROM hashicorp/packer:full\n").ssmImageTag
        val changed = PackerImage(docker(client), "FROM hashicorp/packer:full\nRUN true\n").ssmImageTag

        assertThat(first).isEqualTo(same).isNotEqualTo(changed)
        assertThat(first).matches("localhost/easy-db-lab/packer-ssm:[0-9a-f]{12}")
    }

    @Test
    fun `a failed build reports the end of the build output`() {
        val client = RecordingDockerClient(failBuildWith = listOf("Step 2/2 : RUN ...", "Error relocating session-manager-plugin"))

        assertThatThrownBy { PackerImage(docker(client)).ensure(SshTransport.Ssm) }
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("Error relocating session-manager-plugin")
    }
}
