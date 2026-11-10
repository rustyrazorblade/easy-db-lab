package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
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
 */
class CommandFailureReporter(
    private val eventBus: EventBus,
    private val handlers: List<ProviderErrorHandler>,
) {
    /** Reports [error] as one event, unless the command already reported it. */
    fun report(error: Throwable) {
        if (error is CommandFailedException) {
            log.debug(error) { "Command reported failure" }
            return
        }
        log.error(error) { "Command execution failed" }
        val chain = causeChain(error)
        val description = chain.firstNotNullOfOrNull { cause -> handlers.firstNotNullOfOrNull { it.describe(cause) } }
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

    private fun causeChain(error: Throwable): List<Throwable> {
        val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        return generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.toList()
    }
}
