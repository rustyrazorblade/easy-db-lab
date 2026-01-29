package com.rustyrazorblade.easydblab.output

import com.github.dockerjava.api.model.Frame
import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel

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
 * Sealed interface representing all types of output events that can be sent through a Channel.
 * This enables type-safe, async processing of output from Docker containers and other sources.
 */
sealed interface OutputEvent {
    /**
     * Event representing Docker container frame output (stdout/stderr).
     */
    data class FrameEvent(
        val frame: Frame,
    ) : OutputEvent

    /**
     * Event representing a generic message (e.g., status updates).
     */
    data class MessageEvent(
        val message: String,
    ) : OutputEvent

    /**
     * Event representing an error message with optional throwable.
     */
    data class ErrorEvent(
        val message: String,
        val throwable: Throwable? = null,
    ) : OutputEvent

    /**
     * Event indicating that output handling is complete.
     */
    data object CloseEvent : OutputEvent
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

/**
 * Output handler that sends all output events to a Kotlin Channel for async processing.
 * This enables reactive processing of Docker container output using coroutines.
 *
 * The handler uses `trySend()` to avoid blocking the calling thread. If the channel
 * is closed or full, a warning is logged but execution continues.
 *
 * Example usage:
 * ```kotlin
 * val outputChannel = Channel<OutputEvent>(capacity = Channel.UNLIMITED)
 * val channelHandler = ChannelOutputHandler(outputChannel)
 *
 * // Use with Docker or other systems
 * val docker = Docker(context, dockerClient, userIdProvider)
 *
 * // Process events asynchronously
 * launch {
 *     for (event in outputChannel) {
 *         when (event) {
 *             is OutputEvent.FrameEvent -> handleFrame(event.frame)
 *             is OutputEvent.MessageEvent -> handleMessage(event.message)
 *             is OutputEvent.ErrorEvent -> handleError(event.message, event.throwable)
 *             is OutputEvent.CloseEvent -> break
 *         }
 *     }
 * }
 * ```
 *
 * @param channel The Channel to send output events to
 */
class ChannelOutputHandler(
    private val channel: Channel<OutputEvent>,
) : OutputHandler {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun handleFrame(frame: Frame) {
        val event = OutputEvent.FrameEvent(frame)
        sendEvent(event)
    }

    override fun handleMessage(message: String) {
        val event = OutputEvent.MessageEvent(message)
        sendEvent(event)
    }

    override fun handleError(
        message: String,
        throwable: Throwable?,
    ) {
        val event = OutputEvent.ErrorEvent(message, throwable)
        sendEvent(event)
    }

    override fun close() {
        val event = OutputEvent.CloseEvent
        sendEvent(event)
        log.debug { "Closing channel output handler" }
    }

    private fun sendEvent(event: OutputEvent) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            log.warn { "Failed to send event to channel: $event" }
        }
    }
}

/**
 * Output handler that filters frame events and sends periodic docker activity updates.
 * Drops individual frame events to reduce noise, but sends a progress message every 100 frames.
 * All other event types (MessageEvent, ErrorEvent, CloseEvent) are passed through unchanged.
 *
 * This handler is designed for MCP streaming where frame-by-frame output would be too verbose,
 * but users still need to know that Docker containers are actively running.
 *
 * @param channel The Channel to send filtered output events to
 */
class FilteringChannelOutputHandler(
    private val channel: Channel<OutputEvent>,
) : OutputHandler {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private var frameCount = 0

    /**
     * Reset the frame count to 0.
     * This should be called before each new tool execution to ensure frame counting starts fresh.
     */
    fun resetFrameCount() {
        frameCount = 0
    }

    override fun handleFrame(frame: Frame) {
        frameCount++
        if (frameCount % Constants.Docker.FRAME_REPORTING_INTERVAL == 0) {
            val activityMessage = "Docker container activity: $frameCount frames processed"
            val event = OutputEvent.MessageEvent(activityMessage)
            sendEvent(event)
        }
        // Drop individual frame events - don't send to channel
    }

    override fun handleMessage(message: String) {
        val event = OutputEvent.MessageEvent(message)
        sendEvent(event)
    }

    override fun handleError(
        message: String,
        throwable: Throwable?,
    ) {
        val event = OutputEvent.ErrorEvent(message, throwable)
        sendEvent(event)
    }

    override fun close() {
        val event = OutputEvent.CloseEvent
        sendEvent(event)
        log.debug { "Closing filtering channel output handler" }
    }

    private fun sendEvent(event: OutputEvent) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            log.warn { "Failed to send event to channel: $event" }
        }
    }
}

/**
 * Display observability stack access information.
 * Used by both K8Apply (after deployment) and Status (for reference).
 */
fun OutputHandler.displayObservabilityAccess(controlNodeIp: String) {
    handleMessage("")
    handleMessage("Observability:")
    handleMessage("  Grafana:         http://$controlNodeIp:${Constants.K8s.GRAFANA_PORT}")
    handleMessage("  VictoriaMetrics: http://$controlNodeIp:${Constants.K8s.VICTORIAMETRICS_PORT}")
    handleMessage("  VictoriaLogs:    http://$controlNodeIp:${Constants.K8s.VICTORIALOGS_PORT}")
    handleMessage("")
}

/**
 * Display ClickHouse access information.
 * Used by both ClickHouseStatus (after start) and Status (for reference).
 * @param dbNodeIp IP address of a db node where ClickHouse pods are scheduled
 */
fun OutputHandler.displayClickHouseAccess(dbNodeIp: String) {
    handleMessage("")
    handleMessage("ClickHouse:")
    handleMessage("  Play UI:         http://$dbNodeIp:${Constants.ClickHouse.HTTP_PORT}/play")
    handleMessage("  HTTP Interface:  http://$dbNodeIp:${Constants.ClickHouse.HTTP_PORT}")
    handleMessage("  Native Protocol: $dbNodeIp:${Constants.ClickHouse.NATIVE_PORT}")
}

/**
 * Display S3Manager access information.
 * @param controlNodeIp IP address of the control node where S3Manager runs
 * @param bucketName S3 bucket name to link directly to
 */
fun OutputHandler.displayS3ManagerAccess(
    controlNodeIp: String,
    bucketName: String,
) {
    handleMessage("")
    handleMessage("S3 Manager:")
    handleMessage("  Web UI: http://$controlNodeIp:${Constants.K8s.S3MANAGER_PORT}/buckets/$bucketName")
}

/**
 * Display S3Manager access information for ClickHouse data directory.
 * @param controlNodeIp IP address of the control node where S3Manager runs
 * @param bucketName S3 bucket name
 */
fun OutputHandler.displayS3ManagerClickHouseAccess(
    controlNodeIp: String,
    bucketName: String,
) {
    handleMessage("")
    handleMessage("S3 Manager:")
    handleMessage("  ClickHouse Data: http://$controlNodeIp:${Constants.K8s.S3MANAGER_PORT}/buckets/$bucketName/clickhouse/")
}

/**
 * Display container registry access information with Jib push instructions.
 * @param controlNodeIp IP address of the control node where the registry runs
 * @param socksPort SOCKS5 proxy port (defaults to 1080)
 */
fun OutputHandler.displayRegistryAccess(
    controlNodeIp: String,
    socksPort: Int = Constants.Proxy.DEFAULT_SOCKS5_PORT,
) {
    val registryUrl = "$controlNodeIp:${Constants.K8s.REGISTRY_PORT}"
    handleMessage(
        """
        |
        |=== CONTAINER REGISTRY ===
        |Registry URL: $registryUrl
        |
        |Push images with Gradle Jib (no build.gradle changes required):
        |
        |  1. Ensure SOCKS proxy is running:
        |     source env.sh
        |
        |  2. Build and push:
        |     ./gradlew jib \
        |       -Djib.to.image=$registryUrl/your-image:tag \
        |       -Djib.allowInsecureRegistries=true \
        |       -Djib.httpTimeout=60000 \
        |       -DsocksProxyHost=localhost \
        |       -DsocksProxyPort=$socksPort
        |
        |  Or use environment variable:
        |     export JAVA_TOOL_OPTIONS="-DsocksProxyHost=localhost -DsocksProxyPort=$socksPort"
        |     ./gradlew jib -Djib.to.image=$registryUrl/your-image:tag -Djib.allowInsecureRegistries=true
        """.trimMargin(),
    )
}
