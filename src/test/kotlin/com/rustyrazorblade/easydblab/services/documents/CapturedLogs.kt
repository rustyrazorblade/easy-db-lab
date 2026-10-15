package com.rustyrazorblade.easydblab.services.documents

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * The log lines written under one logger name while a block runs, for tests that check what an
 * operator reads in the log when an upload gives a key up.
 */
class CapturedLogs private constructor(
    private val events: List<ILoggingEvent>,
) {
    /** The formatted messages logged at [level]. */
    fun at(level: Level): List<String> = events.filter { it.level == level }.map { it.formattedMessage }

    /** Captures logs. */
    companion object {
        /** Runs [block] and returns what was logged under [loggerName] and below while it ran. */
        fun during(
            loggerName: String,
            block: () -> Unit,
        ): CapturedLogs {
            val logger = LoggerFactory.getLogger(loggerName) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
            }
            return CapturedLogs(appender.list.toList())
        }
    }
}
