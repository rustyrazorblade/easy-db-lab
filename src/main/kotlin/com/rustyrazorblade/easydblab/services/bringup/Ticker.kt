package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.EventContext
import java.time.Duration

/**
 * Runs an action every interval on a virtual thread of its own, for the bring-up's periodic work:
 * the instance state poll, the control-plane health sample and the progress line. The thread
 * carries the caller's command name, so the events it emits keep it.
 */
object Ticker {
    /**
     * Runs [action] every [interval] until the returned handle is closed: at once and then each
     * interval, or, with [delayFirst], first after one interval.
     */
    fun every(
        name: String,
        interval: Duration,
        delayFirst: Boolean = false,
        action: () -> Unit,
    ): AutoCloseable {
        val commandName = EventContext.current()
        val thread =
            Thread.ofVirtual().name(name).start {
                commandName?.let(EventContext::push)
                try {
                    if (delayFirst) Thread.sleep(interval)
                    while (!Thread.currentThread().isInterrupted) {
                        action()
                        Thread.sleep(interval)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        return AutoCloseable {
            thread.interrupt()
            thread.join()
        }
    }
}
