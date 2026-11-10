package com.rustyrazorblade.easydblab.providers.docker

import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException

/**
 * Tells the command layer that the Docker daemon cannot be reached, and what to do.
 *
 * It replaces the "check if Docker is running" hint the top-level catch in `Main` printed for every
 * Docker failure. Only a connection failure claims the hint: a refused or broken socket, or a
 * missing unix socket file. docker-java reports a stopped daemon as such an `IOException`, which
 * [com.rustyrazorblade.easydblab.DockerException] wraps. An answer from a running daemon (a 404
 * `NotFoundException`, a `ConflictException`) is not claimed, so its own message reaches the user.
 * The reporter walks the cause chain, so the wrapper needs no case of its own.
 *
 * It claims an exception by type alone and takes no dependencies, so it works before a profile
 * exists.
 */
class DockerErrorHandler : ProviderErrorHandler {
    override fun describe(error: Throwable): ErrorDescription? =
        if (isConnectionFailure(error)) {
            ErrorDescription(
                provider = PROVIDER,
                kind = ProviderErrorKind.UNAVAILABLE,
                detail = error.message.orEmpty(),
                remedy = REMEDY,
            )
        } else {
            null
        }

    private fun isConnectionFailure(error: Throwable): Boolean =
        when (error) {
            is ConnectException, is SocketException -> true
            is IOException -> SOCKET_MESSAGES.any { error.message.orEmpty().contains(it) }
            else -> false
        }

    private companion object {
        const val PROVIDER = "Docker"
        const val REMEDY = "Check that Docker is running, then run the command again."

        /** What a unix-socket client says when nothing listens at the Docker socket. */
        val SOCKET_MESSAGES = listOf("No such file or directory", "Connection refused", "docker.sock")
    }
}
