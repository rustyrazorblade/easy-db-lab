package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Containers
import com.rustyrazorblade.easydblab.Docker
import com.rustyrazorblade.easydblab.configuration.SshTransport
import java.security.MessageDigest

/**
 * Chooses the container image Packer runs in for the profile's SSH transport, and makes sure it is
 * present locally.
 *
 * Under `direct` that is the stock Packer image. Under `ssm`, Packer reaches the builder instance
 * through `ssh_interface = "session_manager"`, which needs the Session Manager plugin on Packer's
 * PATH. The stock image lacks it, so the tool builds a derived image from a Dockerfile packaged in
 * the distribution. The image is tagged by a hash of that Dockerfile, so it is built once, reused
 * after that, and rebuilt only when the Dockerfile changes.
 *
 * @param ssmDockerfile the derived image's Dockerfile; a parameter so tests can vary it
 */
class PackerImage(
    private val docker: Docker,
    private val ssmDockerfile: String = loadSsmDockerfile(),
) {
    private val ssmTag = contentHash(ssmDockerfile)

    /** Full reference of the SSM-capable image for the current Dockerfile. */
    val ssmImageTag: String = "${Constants.Packer.SSM_IMAGE_NAME}:$ssmTag"

    /** Makes sure the image for [transport] is present, pulling or building it, and returns its tag. */
    fun ensure(transport: SshTransport): String =
        when (transport) {
            SshTransport.Direct -> {
                docker.pullImage(Containers.PACKER)
                Containers.PACKER.imageWithTag
            }
            SshTransport.Ssm -> {
                if (!docker.exists(Constants.Packer.SSM_IMAGE_NAME, ssmTag)) {
                    docker.buildImage(ssmDockerfile, ssmImageTag)
                }
                ssmImageTag
            }
        }

    private companion object {
        fun loadSsmDockerfile(): String =
            PackerImage::class.java
                .getResourceAsStream(Constants.Packer.SSM_DOCKERFILE_RESOURCE)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Missing packaged resource ${Constants.Packer.SSM_DOCKERFILE_RESOURCE}")

        fun contentHash(content: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(content.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(Constants.Packer.SSM_IMAGE_TAG_LENGTH)
    }
}
