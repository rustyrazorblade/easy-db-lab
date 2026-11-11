package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Collections
import java.util.IdentityHashMap

private val log = KotlinLogging.logger {}

/**
 * Turns a failed command's exception into exactly one event.
 *
 * It walks the cause chain from the outermost exception inward and asks every registered
 * [ProviderErrorHandler] about each level. The first match becomes [Event.Command.ProviderError],
 * with its remedy. When no handler claims anything, the failure stays today's
 * [Event.Command.ExecutionError] with the full cause chain. A [CommandFailedException] means the
 * command already told the user what happened, so nothing more is emitted.
 *
 * Shared by the command lifecycle, the top-level dispatch and Picocli's exception handler, so a
 * failure reads the same wherever it surfaces. The handlers take no profile, which lets this build
 * before one exists.
 *
 * Precedence is outermost cause first, then handler registration order, so a broad handler that claims a
 * wrapper exception shadows a more specific handler for an inner cause. That matters once a third provider
 * exists; keep each handler narrow.
 */
class CommandFailureReporter(
    private val eventBus: EventBus,
    private val handlers: List<ProviderErrorHandler>,
) {
    /** Reports [error] as one event, unless the command already reported it. */
    fun report(error: Throwable) {
        val chain = causeChain(error)
        // A site that already showed its own block rethrows CommandFailedException; a CompletionException
        // or ExecutionException around it must not make the user see a second block.
        if (chain.any { it is CommandFailedException }) {
            log.debug(error) { "Command reported failure" }
            return
        }
        log.error(error) { "Command execution failed" }
        val description = chain.firstNotNullOfOrNull { cause -> handlers.firstNotNullOfOrNull { describeSafely(it, cause) } }
        if (description != null) {
            eventBus.emit(
                Event.Command.ProviderError(
                    provider = description.provider,
                    kind = description.kind,
                    detail = description.detail,
                    remedy = description.remedy,
                ),
            )
        } else {
            eventBus.emit(
                Event.Command.ExecutionError(
                    chain.joinToString("\n  caused by: ") { "${it::class.simpleName}: ${it.message}" },
                ),
            )
        }
    }

    /** A handler that itself fails must not hide the failure being reported, so it counts as not claiming. */
    private fun describeSafely(
        handler: ProviderErrorHandler,
        cause: Throwable,
    ): ErrorDescription? =
        try {
            handler.describe(cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Provider error handler ${handler::class.simpleName} failed on ${cause::class.simpleName}" }
            null
        }

    private fun causeChain(error: Throwable): List<Throwable> {
        val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        return generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.toList()
    }
}
