package com.rustyrazorblade.easydblab.providers.docker

import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind

/**
 * Tells the command layer that a Docker failure means the daemon is not reachable, and what to do.
 *
 * It replaces the "check if Docker is running" hint the top-level catch in `Main` used to print.
 * It claims an exception by type alone (docker-java's and the project's own wrapper) and takes no
 * dependencies, so it works before a profile exists.
 */
class DockerErrorHandler : ProviderErrorHandler {
    override fun describe(error: Throwable): ErrorDescription? =
        when (error) {
            is com.github.dockerjava.api.exception.DockerException, is DockerException ->
                ErrorDescription(
                    provider = PROVIDER,
                    kind = ProviderErrorKind.UNAVAILABLE,
                    detail = error.message.orEmpty(),
                    remedy = REMEDY,
                )
            else -> null
        }

    private companion object {
        const val PROVIDER = "Docker"
        const val REMEDY = "Check that Docker is running, then run the command again."
    }
}
