package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.McpEventListener
import com.rustyrazorblade.easydblab.output.CompositeOutputHandler
import com.rustyrazorblade.easydblab.output.FilteringChannelOutputHandler
import com.rustyrazorblade.easydblab.output.OutputEvent
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlin.getValue

@Serializable
data class ServerStatusResponse(
    val status: String,
    val command: String,
    @SerialName("runtime_seconds") val runtimeSeconds: Long,
    val messages: List<String>,
    val timestamp: String,
)

/** MCP server implementation using the official SDK. */
class McpServer(
    private val refreshIntervalSeconds: Long = DEFAULT_REFRESH_INTERVAL_SECONDS,
) : KoinComponent {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val DEFAULT_REFRESH_INTERVAL_SECONDS = 30L
    }

    private val context: Context by inject()
    private val outputHandler: OutputHandler by inject()
    private val eventBus: EventBus by inject()

    private val toolRegistry = McpToolRegistry()
    private val mcpEventListener = McpEventListener()
    private val executionSemaphore = Semaphore(1) // Only allow one tool execution at a time
    private val statusCache = StatusCache(refreshIntervalSeconds)

    // Status tracking components
    private val outputChannel = Channel<OutputEvent>(Channel.UNLIMITED)
    private lateinit var streamingHandler: FilteringChannelOutputHandler
    private var streamingInitialized = false
    private val messageBuffer = ChannelMessageBuffer(outputChannel)
    private var currentCommand: String? = null
    private var commandStartTime: Long? = null

    /**
     * Initialize streaming functionality by adding FilteringChannelOutputHandler to the
     * CompositeOutputHandler. This method is idempotent - it will not add duplicate handlers if
     * called multiple times.
     */
    fun initializeStreaming() {
        if (streamingInitialized) {
            log.debug { "Streaming already initialized, skipping" }
            return
        }

        streamingHandler = FilteringChannelOutputHandler(outputChannel)

        val compositeHandler = outputHandler as CompositeOutputHandler
        compositeHandler.addHandler(streamingHandler)
        streamingInitialized = true
        log.info { "Streaming handler added to composite output handler" }

        // Register EventBus listener for structured events
        eventBus.addListener(mcpEventListener)
        log.info { "McpEventListener registered with EventBus" }
    }

    /**
     * Creates a handler for the get_server_status tool that returns current execution status and messages.
     */
    private fun createStatusHandler(): (CallToolRequest) -> CallToolResult =
        { _ ->
            val isRunning = executionSemaphore.availablePermits() == 0
            val status =
                when {
                    isRunning -> "running"
                    currentCommand != null -> "completed"
                    else -> "idle"
                }

            val runtimeSeconds =
                if (commandStartTime != null) {
                    (System.currentTimeMillis() - commandStartTime!!) /
                        Constants.Time.MILLIS_PER_SECOND
                } else {
                    0
                }

            // Get accumulated messages from both old OutputHandler path and new EventBus path
            val oldMessages = messageBuffer.getAndClearMessages()
            val eventMessages =
                mcpEventListener.getAndClearEnvelopes().map { envelope ->
                    "[${envelope.timestamp}] ${envelope.event.toDisplayString()}"
                }
            val messages = oldMessages + eventMessages

            val statusResponse =
                ServerStatusResponse(
                    status = status,
                    command = currentCommand ?: "none",
                    runtimeSeconds = runtimeSeconds,
                    messages = messages,
                    timestamp =
                        java.time.Instant
                            .now()
                            .toString(),
                )

            // Clear command tracking if completed
            if (status == "completed") {
                currentCommand = null
                commandStartTime = null
            }

            CallToolResult(
                content = listOf(TextContent(text = Json.encodeToString(statusResponse))),
                isError = false,
            )
        }

    /**
     * Creates a tool handler for background execution with proper error handling and status
     * tracking.
     */
    private fun createToolHandler(): (CallToolRequest) -> CallToolResult =
        { request ->
            // Try to acquire semaphore without blocking
            if (!executionSemaphore.tryAcquire()) {
                // Another command is already running
                CallToolResult(
                    content =
                        listOf(
                            TextContent(
                                text =
                                    "Another tool is already running. " +
                                        "Please wait for it to complete or check status with 'get_server_status'.",
                            ),
                        ),
                    isError = false,
                )
            } else {
                // Launch background execution using simple Thread
                thread(start = true) {
                    try {
                        log.info { "Starting background execution of tool: ${request.name}" }

                        // Track command execution
                        currentCommand = request.name
                        commandStartTime = System.currentTimeMillis()

                        // Reset frame count for this tool execution
                        if (::streamingHandler.isInitialized) {
                            streamingHandler.resetFrameCount()
                        }

                        toolRegistry.executeTool(request.name, request.arguments)
                        log.info { "Completed background execution of tool: ${request.name}" }
                        eventBus.emit(
                            Event.Message("Background execution of tool '${request.name}' complete."),
                        )
                    } catch (e: RuntimeException) {
                        log.error(e) { "Error in background execution of tool ${request.name}" }
                        eventBus.emit(
                            Event.Error("Background execution of tool '${request.name}' failed: ${e.message}"),
                        )
                    } finally {
                        executionSemaphore.release()
                    }
                }

                // Return immediate response indicating background execution
                CallToolResult(
                    content =
                        listOf(
                            TextContent(
                                text =
                                    "Tool '${request.name}' started in background. " +
                                        "Use 'get_server_status' to monitor progress.",
                            ),
                        ),
                    isError = false,
                )
            }
        }

    private fun registerTools(server: Server) {
        // Get tools from registry (already filtered by @McpCommand annotation)
        val tools = toolRegistry.getTools()

        log.info { "Registering ${tools.size} MCP-enabled tools" }
        log.info { "Tools to register: ${tools.map { it.name }}" }

        tools.forEach { toolInfo ->
            log.info {
                "Registering tool: ${toolInfo.name} with description: ${toolInfo.description}"
            }
            server.addTool(
                name = toolInfo.name,
                description = toolInfo.description,
                handler = createToolHandler(),
            )
        }
    }

    /**
     * Registers a prompt from a PromptResource by creating the MCP prompt handler.
     */
    private fun registerPromptResource(
        server: Server,
        promptResource: PromptResource,
    ) {
        log.info { "Registering prompt: ${promptResource.name}" }
        server.addPrompt(
            name = promptResource.name,
            description = promptResource.description,
        ) { request ->
            GetPromptResult(
                description = promptResource.description,
                messages =
                    listOf(
                        PromptMessage(
                            role = Role.User,
                            content = TextContent(text = promptResource.content),
                        ),
                    ),
            )
        }
    }

    /**
     * Loads and registers all prompts from the classpath resources in com.rustyrazorblade.mcp package.
     */
    private fun registerAllPrompts(server: Server) {
        val loader = PromptLoader()
        val prompts = loader.loadAllPrompts("com.rustyrazorblade.mcp")

        log.info { "Found ${prompts.size} prompts to register" }
        prompts.forEach { promptResource ->
            registerPromptResource(server, promptResource)
        }
    }

    fun start(
        port: Int,
        bind: String = "127.0.0.1",
        onStarted: (actualPort: Int) -> Unit,
    ) {
        try {
            log.info { "Starting MCP server with SDK (version ${context.version})" }
            initializeStreaming()

            val server = createServer()
            registerServerTools(server)
            registerAllPrompts(server)
            messageBuffer.start()
            statusCache.start()

            startEmbeddedServer(port, bind, server, onStarted)
        } catch (e: IllegalStateException) {
            log.error { "Transport error: ${e.message}" }
            throw e
        } catch (e: RuntimeException) {
            log.error(e) { "Unexpected error in MCP server" }
            throw e
        }
    }

    private fun createServer(): Server =
        Server(
            Implementation(
                name = "easy-db-lab",
                version = context.version.toString(),
            ),
            ServerOptions(
                capabilities =
                    ServerCapabilities(
                        tools = ServerCapabilities.Tools(listChanged = true),
                        prompts = ServerCapabilities.Prompts(listChanged = true),
                    ),
            ),
        )

    private fun registerServerTools(server: Server) {
        log.info { "Registering get_server_status tool" }
        server.addTool(
            name = "get_server_status",
            description = "Get the status of background tool execution and accumulated messages",
            handler = createStatusHandler(),
        )
        registerTools(server)
    }

    private fun startEmbeddedServer(
        port: Int,
        bind: String,
        server: Server,
        onStarted: (actualPort: Int) -> Unit,
    ) {
        val serverSessions = ConcurrentMap<String, ServerSession>()

        val ktorServer =
            embeddedServer(Netty, host = bind, port = port) {
                install(SSE)
                routing {
                    swaggerUI("/swagger") {
                        info = OpenApiInfo("easy-db-lab MCP Server", context.version.toString())
                        source = OpenApiDocSource.Routing()
                    }
                    sse("/sse") {
                        val transport = SseServerTransport("/message", this)
                        val serverSession = server.createSession(transport)
                        serverSessions[transport.sessionId] = serverSession

                        serverSession.onClose {
                            log.info { "Server session closed for: ${transport.sessionId}" }
                            serverSessions.remove(transport.sessionId)
                        }
                        awaitCancellation()
                    }
                    post("/message") {
                        val sessionId: String? = call.request.queryParameters["sessionId"]
                        if (sessionId == null) {
                            call.respond(HttpStatusCode.BadRequest, "Missing sessionId parameter")
                            return@post
                        }

                        val transport = serverSessions[sessionId]?.transport as? SseServerTransport
                        if (transport == null) {
                            call.respond(HttpStatusCode.NotFound, "Session not found")
                            return@post
                        }

                        transport.handlePostMessage(call)
                    }
                    get("/stress/status") {
                        val live = call.request.queryParameters["live"]?.toBoolean() ?: false

                        if (live) {
                            statusCache.forceRefresh()
                        }

                        try {
                            val json = statusCache.getStatus("stressJobs")
                            if (json == null) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    "Status not yet available",
                                )
                            } else {
                                call.respondText(json, ContentType.Application.Json)
                            }
                        } catch (e: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
                        }
                    }.describe {
                        summary = "Get stress job status"
                        description = "Returns the current status of Cassandra stress jobs " +
                            "running on the cluster."
                        parameters {
                            query("live") {
                                description = "Force a live refresh of status data before responding"
                                required = false
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "JSON stress job status response"
                                ContentType.Application.Json()
                            }
                            HttpStatusCode.ServiceUnavailable {
                                description = "Status not yet available (cache warming up)"
                            }
                        }
                    }
                    get("/status") {
                        val live = call.request.queryParameters["live"]?.toBoolean() ?: false
                        val section = call.request.queryParameters["section"]

                        if (live) {
                            statusCache.forceRefresh()
                        }

                        try {
                            val json = statusCache.getStatus(section)
                            if (json == null) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    "Status not yet available",
                                )
                            } else {
                                call.respondText(json, ContentType.Application.Json)
                            }
                        } catch (e: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
                        }
                    }.describe {
                        summary = "Get cluster environment status"
                        description = "Returns cached status of the cluster environment " +
                            "including nodes, networking, Cassandra version, and more."
                        parameters {
                            query("live") {
                                description = "Force a live refresh of status data before responding"
                                required = false
                            }
                            query("section") {
                                description = "Return only a specific section. " +
                                    "Valid values: ${StatusCache.VALID_SECTIONS.joinToString(", ")}"
                                required = false
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "JSON status response"
                                ContentType.Application.Json()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Invalid section parameter"
                            }
                            HttpStatusCode.ServiceUnavailable {
                                description = "Status not yet available (cache warming up)"
                            }
                        }
                    }
                }
            }.start(wait = false)

        // Get the actual port (important when port 0 is requested)
        val actualPort =
            kotlinx.coroutines.runBlocking {
                ktorServer.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }

        // Notify caller of actual port before blocking
        onStarted(actualPort)

        eventBus.emit(
            Event.Message(
                """
                Starting MCP server on port $actualPort...

                Server is now available at: http://$bind:$actualPort/sse
                Swagger UI available at:    http://$bind:$actualPort/swagger
                """.trimIndent(),
            ),
        )

        // Wait for shutdown
        Thread.currentThread().join()

        log.info { "MCP server stopped" }
    }
}
