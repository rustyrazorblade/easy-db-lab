package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.Constants
import org.testcontainers.DockerClientFactory
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Path

/**
 * The `cassandra-runtime` image, built here from `docker/cassandra-runtime/Dockerfile` under the
 * tag the cassandra-pods kit pulls (`<revision>-jre<N>`), so the integration tests run the image a
 * change to the Dockerfile produces, not whatever is published.
 *
 * The legacy builder the Docker Java client uses does not set `TARGETARCH`, which the Dockerfile
 * picks yq's checksum by, so it is passed from the daemon's own architecture.
 */
object CassandraRuntimeTestImage {
    private const val DOCKERFILE = "docker/cassandra-runtime/Dockerfile"

    /** The tag the kit names for [java]. */
    fun tag(java: String): String =
        "${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REPOSITORY}:${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION}-jre$java"

    /** Builds the image for [java] into the host's Docker and returns its tag. */
    fun build(java: String): String {
        val tag = tag(java)
        ImageFromDockerfile(tag, false)
            .withDockerfile(Path.of(DOCKERFILE))
            .withBuildArg("JAVA", java)
            .withBuildArg("TARGETARCH", targetArch())
            .get()
        return tag
    }

    private fun targetArch(): String =
        when (val arch = DockerClientFactory.instance().info.architecture) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("The cassandra-runtime image is built for amd64 and arm64, not the Docker host's $arch")
        }
}
