package com.rustyrazorblade.easydblab.server

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Cluster status as the REST endpoints serve it: cached JSON, refreshed on request.
 * [StatusCache] is the one implementation; tests pass their own.
 */
interface CachedStatus {
    /**
     * The status as JSON, the whole response or one [section]; null while none is available.
     *
     * @throws IllegalArgumentException when [section] is not a known section.
     */
    fun getStatus(section: String? = null): String?

    /** Refreshes the status now, before the next request reads it. */
    fun forceRefresh()
}

/** A started [StatusServer]: the port it bound, and [close] to stop it. */
class RunningStatusServer(
    val port: Int,
    private val stop: () -> Unit,
) : AutoCloseable {
    override fun close() = stop()
}

/**
 * The REST status server of `easy-db-lab server`: `/status` and `/stress/status` from [status], and
 * API docs at `/swagger`.  It serves nothing else; there is no MCP endpoint.
 *
 * @param status the cached cluster status the endpoints read
 */
class StatusServer(
    private val status: CachedStatus,
) : KoinComponent {
    private val context: Context by inject()
    private val eventBus: EventBus by inject()

    /**
     * Binds [bind]:[port] (0 for any free port), emits `Server.Ready` naming the status and API docs
     * URLs, and returns the running server.
     */
    fun start(
        port: Int,
        bind: String,
    ): RunningStatusServer {
        val ktorServer =
            embeddedServer(Netty, host = bind, port = port) {
                routing {
                    swaggerUI("/swagger") {
                        info = OpenApiInfo("easy-db-lab Server", context.version.toString())
                        source = OpenApiDocSource.Routing()
                    }
                    configureStatusRoutes()
                }
            }.start(wait = false)
        val actualPort =
            runBlocking {
                ktorServer.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        log.info { "Status server listening on $bind:$actualPort" }
        eventBus.emit(Event.Server.Ready(statusUrl = "http://$bind:$actualPort/status", swaggerUrl = "http://$bind:$actualPort/swagger"))
        return RunningStatusServer(actualPort) { ktorServer.stop() }
    }

    @OptIn(ExperimentalKtorApi::class)
    private fun Routing.configureStatusRoutes() {
        get("/stress/status") {
            val live = call.request.queryParameters["live"]?.toBoolean() ?: false
            if (live) status.forceRefresh()
            respondWithCachedStatus("stressJobs")
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
            if (live) status.forceRefresh()
            respondWithCachedStatus(section)
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

    private suspend fun RoutingContext.respondWithCachedStatus(section: String?) {
        try {
            val json = status.getStatus(section)
            if (json == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, "Status not yet available")
            } else {
                call.respondText(json, ContentType.Application.Json)
            }
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
