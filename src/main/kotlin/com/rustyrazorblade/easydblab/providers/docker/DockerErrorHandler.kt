package com.rustyrazorblade.easydblab.providers.docker

import com.github.dockerjava.api.exception.DockerClientException
import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.kernel.causeChain
import java.net.ConnectException
import java.net.SocketException
import java.net.URI

/**
 * Tells the command layer that the Docker daemon cannot be reached, and what to do.
 *
 * It replaces the "check if Docker is running" hint the top-level catch in `Main` printed for every
 * Docker failure. A connection failure alone proves nothing: a refused K3s API, SOCKS tunnel, Redis
 * or SSH connection looks the same. So it claims a failure only when it is tied to Docker:
 *
 * - a connection failure sits below a Docker exception (docker-java's, or the project's
 *   [DockerException]) in the cause chain, or
 * - a connection failure names the Docker socket (`docker.sock`) or the configured `DOCKER_HOST`
 *   (its host and port, or its socket path for a `unix://` host).
 *
 * docker-java itself reports a missing or dead socket as a bare `RuntimeException` around a
 * `SocketException` that names no path; `DefaultDockerClient` rethrows that as a [DockerException],
 * which is what puts it under the first rule.
 *
 * The detail is the message of the exception that matched, not of whatever wraps it. A file or
 * bind-mount error that names the socket is not a connection failure and is not claimed. An answer
 * from a running daemon (a 404 `NotFoundException`, a `ConflictException`) carries no connection
 * failure and is not claimed either, so its own message reaches the user.
 *
 * It claims an exception by type and message alone and has no collaborators, so it works before a
 * profile exists. The configured Docker host is read from the environment by default.
 *
 * @param dockerHost the `DOCKER_HOST` value, or null when the default socket is used
 */
class DockerErrorHandler(
    dockerHost: String? = System.getenv(DOCKER_HOST_ENV),
) : ProviderErrorHandler {
    private val endpointMarkers: List<String>? = dockerHost?.let(::markersOf)

    override fun describe(error: Throwable): ErrorDescription? {
        val chain = error.causeChain()
        val matched =
            chain
                .withIndex()
                .firstOrNull { (index, cause) ->
                    cause.isDockerFailure() && chain.drop(index + 1).any { it.isConnectionFailure() }
                }?.value ?: chain.firstOrNull { it.isConnectionFailure() && namesDockerEndpoint(it.message.orEmpty()) }
        return matched?.let {
            ErrorDescription(
                provider = PROVIDER,
                kind = ProviderErrorKind.UNAVAILABLE,
                detail = it.message.orEmpty(),
                remedy = REMEDY,
            )
        }
    }

    private fun Throwable.isDockerFailure(): Boolean =
        this is DockerException ||
            this is DockerClientException ||
            this is com.github.dockerjava.api.exception.DockerException

    /**
     * A failure to reach an endpoint. docker-java's httpclient5 transport reports a missing unix socket as a
     * `SocketException` ("No such file or directory") and a refused `DOCKER_HOST` as `HttpHostConnectException`,
     * a `ConnectException`. A plain `IOException` (a file copy or a bind mount that names the socket) is not one.
     */
    private fun Throwable.isConnectionFailure(): Boolean = this is ConnectException || this is SocketException

    private fun namesDockerEndpoint(message: String): Boolean =
        message.contains(SOCKET_FILE) ||
            endpointMarkers?.let { markers -> markers.all { message.contains(it) } } == true

    /**
     * What a failure message must contain to name the configured endpoint: the host and `:port` of a
     * `tcp://` host, or the socket path of a `unix://` one (colima, Rancher Desktop and Docker
     * Desktop's per-user socket all use one, and a unix URI has no host).
     */
    private fun markersOf(value: String): List<String>? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return when {
            uri.scheme == UNIX_SCHEME -> uri.path?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
            uri.host != null && uri.port != -1 -> listOf(uri.host, ":${uri.port}")
            else -> null
        }
    }

    private companion object {
        const val PROVIDER = "Docker"
        const val REMEDY = "Check that Docker is running, then run the command again."
        const val DOCKER_HOST_ENV = "DOCKER_HOST"
        const val UNIX_SCHEME = "unix"

        /** The default Docker daemon socket, which a unix-socket client names when it cannot open it. */
        const val SOCKET_FILE = "docker.sock"
    }
}
