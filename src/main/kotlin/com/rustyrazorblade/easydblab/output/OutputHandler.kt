package com.rustyrazorblade.easydblab.output

import com.github.dockerjava.api.model.Frame
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Interface for handling output from the application.
 * Allows different implementations for console, logger, buffer, etc.
 */
interface OutputHandler {
    /**
     * Handle a frame of output from a Docker container.
     *
     * @param frame The frame containing output data
     */
    fun handleFrame(frame: Frame)

    /**
     * Handle a generic message (e.g., status updates).
     *
     * @param message The message to handle
     */
    fun handleMessage(message: String)

    /**
     * Handle an error message.
     *
     * @param message The error message
     * @param throwable Optional throwable associated with the error
     */
    fun handleError(
        message: String,
        throwable: Throwable? = null,
    )

    /**
     * Called when output handling is complete.
     */
    fun close()
}

/**
 * Output handler that writes to console (stdout/stderr).
 * This is the default handler that mimics current behavior.
 */
class ConsoleOutputHandler : OutputHandler {
    override fun handleFrame(frame: Frame) {
        val payloadStr = String(frame.payload)

        when (frame.streamType.name) {
            "STDOUT" -> print(payloadStr)
            "STDERR" -> System.err.print(payloadStr)
            "RAW" -> print(payloadStr) // Handle RAW frames as stdout
            else -> { /* Ignore unknown stream types */ }
        }
    }

    override fun handleMessage(message: String) {
        println(message)
    }

    override fun handleError(
        message: String,
        throwable: Throwable?,
    ) {
        System.err.println(message)
        throwable?.let { System.err.println(it.toString()) }
    }

    override fun close() {
        // No-op for console output
    }
}

/**
 * Output handler that writes to a logger.
 * Suitable for background execution and structured logging.
 */
class LoggerOutputHandler(
    private val loggerName: String = "DockerContainer",
) : OutputHandler {
    private val log = KotlinLogging.logger(loggerName)

    override fun handleFrame(frame: Frame) {
        val payloadStr = String(frame.payload).trimEnd()

        when (frame.streamType.name) {
            "STDOUT" -> log.info { payloadStr }
            "STDERR" -> log.error { payloadStr }
            else -> log.debug { "Unknown stream type: ${frame.streamType.name}: $payloadStr" }
        }
    }

    override fun handleMessage(message: String) {
        log.info { message }
    }

    override fun handleError(
        message: String,
        throwable: Throwable?,
    ) {
        throwable?.let {
            log.error(it) { message }
        } ?: log.error { message }
    }

    override fun close() {
        log.debug { "Closing logger output handler" }
    }
}

/**
 * Output handler that buffers all output for later retrieval.
 * Useful for testing and capturing output programmatically.
 * Thread-safe for concurrent access.
 */
class BufferedOutputHandler : OutputHandler {
    private val stdoutBuffer = StringBuilder()
    private val stderrBuffer = StringBuilder()
    private val messagesBuffer = mutableListOf<String>()
    private val errorsBuffer = mutableListOf<Pair<String, Throwable?>>()
    private val lock = Any()

    val stdout: String get() = stdoutBuffer.toString()
    val stderr: String get() = stderrBuffer.toString()
    val messages: List<String> get() = messagesBuffer.toList()
    val errors: List<Pair<String, Throwable?>> get() = errorsBuffer.toList()

    override fun handleFrame(frame: Frame) {
        val payloadStr = String(frame.payload)

        synchronized(lock) {
            when (frame.streamType.name) {
                "STDOUT", "RAW" -> stdoutBuffer.append(payloadStr)
                "STDERR" -> stderrBuffer.append(payloadStr)
                else -> { /* Ignore unknown stream types */ }
            }
        }
    }

    override fun handleMessage(message: String) {
        synchronized(lock) {
            messagesBuffer.add(message)
        }
    }

    override fun handleError(
        message: String,
        throwable: Throwable?,
    ) {
        synchronized(lock) {
            errorsBuffer.add(message to throwable)
        }
    }

    override fun close() {
        // No-op for buffered output
    }

    /**
     * Clear all buffers.
     */
    fun clear() {
        synchronized(lock) {
            stdoutBuffer.clear()
            stderrBuffer.clear()
            messagesBuffer.clear()
            errorsBuffer.clear()
        }
    }
}

/**
 * Composite output handler that delegates to multiple handlers.
 * Useful for writing to both console and logs simultaneously.
 * Supports dynamic addition and removal of handlers at runtime.
 * Thread-safe for concurrent access and modifications.
 *
 * Example usage:
 * ```kotlin
 * val composite = CompositeOutputHandler()
 * composite.addHandler(ConsoleOutputHandler())
 * composite.addHandler(LoggerOutputHandler("MyApp"))
 *
 * // Later, add more handlers dynamically
 * composite.addHandler(BufferedOutputHandler())
 * ```
 */
class CompositeOutputHandler(
    handlers: List<OutputHandler> = emptyList(),
) : OutputHandler {
    private val handlers: MutableList<OutputHandler> = handlers.toMutableList()

    constructor(vararg handlers: OutputHandler) : this(handlers.toList())

    /**
     * Add a new handler to the composite.
     * The handler will receive all subsequent output.
     *
     * @param handler The OutputHandler to add
     * @throws IllegalArgumentException if handler is already present
     */
    fun addHandler(handler: OutputHandler) {
        synchronized(handlers) {
            require(!handlers.contains(handler)) { "Handler already exists in composite" }
            handlers.add(handler)
        }
    }

    /**
     * Remove a handler from the composite.
     * The handler will no longer receive output.
     *
     * @param handler The OutputHandler to remove
     * @return true if handler was removed, false if it wasn't present
     */
    fun removeHandler(handler: OutputHandler): Boolean =
        synchronized(handlers) {
            handlers.remove(handler)
        }

    /**
     * Remove all handlers from the composite.
     * After this call, no output will be processed until new handlers are added.
     */
    fun removeAllHandlers() {
        synchronized(handlers) {
            handlers.clear()
        }
    }

    /**
     * Get the current number of handlers in the composite.
     *
     * @return The number of active handlers
     */
    fun getHandlerCount(): Int =
        synchronized(handlers) {
            handlers.size
        }

    /**
     * Check if a specific handler is present in the composite.
     *
     * @param handler The OutputHandler to check for
     * @return true if handler is present, false otherwise
     */
    fun hasHandler(handler: OutputHandler): Boolean =
        synchronized(handlers) {
            handlers.contains(handler)
        }

    /**
     * Get a copy of all current handlers.
     * This is a defensive copy to prevent external modification.
     *
     * @return List of current handlers
     */
    fun getHandlers(): List<OutputHandler> =
        synchronized(handlers) {
            handlers.toList()
        }

    override fun handleFrame(frame: Frame) {
        val currentHandlers =
            synchronized(handlers) {
                handlers.toList() // Copy for safe iteration
            }
        currentHandlers.forEach { it.handleFrame(frame) }
    }

    override fun handleMessage(message: String) {
        val currentHandlers =
            synchronized(handlers) {
                handlers.toList() // Copy for safe iteration
            }
        currentHandlers.forEach { it.handleMessage(message) }
    }

    override fun handleError(
        message: String,
        throwable: Throwable?,
    ) {
        val currentHandlers =
            synchronized(handlers) {
                handlers.toList() // Copy for safe iteration
            }
        currentHandlers.forEach { it.handleError(message, throwable) }
    }

    override fun close() {
        val currentHandlers =
            synchronized(handlers) {
                handlers.toList() // Copy for safe iteration
            }
        currentHandlers.forEach { it.close() }
    }
}
