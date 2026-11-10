package com.rustyrazorblade.easydblab.providers.docker

import com.github.dockerjava.api.exception.DockerClientException
import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Tells the command layer that the Docker daemon cannot be reached, and what to do.
 *
 * It replaces the "check if Docker is running" hint the top-level catch in `Main` printed for every
 * Docker failure. A connection failure alone proves nothing: a refused K3s API, SOCKS tunnel, Redis
 * or SSH connection looks the same. So it claims a failure only when it is tied to Docker:
 *
 * - a connection failure sits in the cause chain of a Docker exception (docker-java's, or the
 *   project's [DockerException]), or
 * - an `IOException` names the Docker socket (`docker.sock`) or the configured `DOCKER_HOST`.
 *
 * An answer from a running daemon (a 404 `NotFoundException`, a `ConflictException`) carries no
 * connection failure and is not claimed, so its own message reaches the user.
 *
 * It claims an exception by type and message alone and has no collaborators, so it works before a
 * profile exists. The configured Docker host is read from the environment by default.
 *
 * @param dockerHost the `DOCKER_HOST` value, or null when the default socket is used
 */
class DockerErrorHandler(
    dockerHost: String? = System.getenv(DOCKER_HOST_ENV),
) : ProviderErrorHandler {
    private val hostMarkers: Pair<String, String>? = dockerHost?.let(::hostAndPort)

    override fun describe(error: Throwable): ErrorDescription? {
        val chain = causeChain(error)
        val tiedToDocker = chain.any { it.isDockerFailure() } && chain.any { it.isConnectionFailure() }
        val namesDocker = chain.any { it is IOException && namesDockerEndpoint(it.message.orEmpty()) }
        return if (tiedToDocker || namesDocker) {
            ErrorDescription(
                provider = PROVIDER,
                kind = ProviderErrorKind.UNAVAILABLE,
                detail = error.message.orEmpty(),
                remedy = REMEDY,
            )
        } else {
            null
        }
    }

    private fun Throwable.isDockerFailure(): Boolean =
        this is DockerException ||
            this is DockerClientException ||
            this is com.github.dockerjava.api.exception.DockerException

    private fun Throwable.isConnectionFailure(): Boolean = this is ConnectException || this is SocketException

    private fun namesDockerEndpoint(message: String): Boolean =
        message.contains(SOCKET_FILE) ||
            hostMarkers?.let { (host, port) -> message.contains(host) && message.contains(":$port") } == true

    private fun hostAndPort(value: String): Pair<String, String>? =
        runCatching { URI(value) }
            .getOrNull()
            ?.takeIf { it.host != null && it.port != -1 }
            ?.let { it.host to it.port.toString() }

    private fun causeChain(error: Throwable): List<Throwable> {
        val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        return generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.toList()
    }

    private companion object {
        const val PROVIDER = "Docker"
        const val REMEDY = "Check that Docker is running, then run the command again."
        const val DOCKER_HOST_ENV = "DOCKER_HOST"

        /** The default Docker daemon socket, which a unix-socket client names when it cannot open it. */
        const val SOCKET_FILE = "docker.sock"
    }
}
