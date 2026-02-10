package com.rustyrazorblade.cassandrametrics

import com.rustyrazorblade.cassandrametrics.cache.MetricsCache
import com.rustyrazorblade.cassandrametrics.collector.MetricsCollector
import com.rustyrazorblade.cassandrametrics.collector.VirtualTableRegistry
import com.rustyrazorblade.cassandrametrics.session.CassandraSessionFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import picocli.CommandLine

private val log = KotlinLogging.logger {}

@CommandLine.Command(
    name = "server",
    description = ["Start the metrics collection server"],
    mixinStandardHelpOptions = true,
)
class ServerCommand : Runnable {
    @CommandLine.Option(
        names = ["--contact-point"],
        description = ["Cassandra contact point"],
        defaultValue = "127.0.0.1",
    )
    lateinit var contactPoint: String

    @CommandLine.Option(
        names = ["--port"],
        description = ["Cassandra CQL native port"],
        defaultValue = "9042",
    )
    var cqlPort: Int = 9042

    @CommandLine.Option(
        names = ["--metrics-port"],
        description = ["Prometheus metrics endpoint port"],
        defaultValue = "9601",
    )
    var metricsPort: Int = 9601

    @CommandLine.Option(
        names = ["--poll-interval"],
        description = ["Seconds between collection cycles"],
        defaultValue = "5",
    )
    var pollInterval: Long = 5

    @CommandLine.Option(
        names = ["--include-system-keyspaces"],
        description = ["Include system keyspaces in per-table metrics"],
        defaultValue = "false",
    )
    var includeSystemKeyspaces: Boolean = false

    @CommandLine.Option(
        names = ["--datacenter"],
        description = ["Local datacenter for the Cassandra driver"],
        defaultValue = "datacenter1",
    )
    lateinit var datacenter: String

    @CommandLine.Option(
        names = ["--threads"],
        description = ["Thread pool size for query execution"],
        defaultValue = "4",
    )
    var threads: Int = 4

    override fun run() {
        println("Starting Cassandra Metrics Collector")
        println("  Contact point: $contactPoint:$cqlPort")
        println("  Datacenter: $datacenter")
        println("  Metrics port: $metricsPort")
        println("  Poll interval: ${pollInterval}s")
        println("  Threads: $threads")
        println("  Include system keyspaces: $includeSystemKeyspaces")

        val session = CassandraSessionFactory.create(contactPoint, cqlPort, datacenter)
        val cache = MetricsCache()
        val registry = VirtualTableRegistry.buildDefault()

        val collector =
            MetricsCollector(
                session = session,
                cache = cache,
                registry = registry,
                pollIntervalSeconds = pollInterval,
                threadPoolSize = threads,
                includeSystemKeyspaces = includeSystemKeyspaces,
            )

        collector.start()
        println("Metrics collector started")

        val server =
            embeddedServer(Netty, port = metricsPort) {
                routing {
                    get("/metrics") {
                        call.respondText(cache.renderAll(), ContentType.Text.Plain)
                    }
                    get("/health") {
                        call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
                    }
                }
            }

        log.info { "Starting HTTP server on port $metricsPort" }
        println("HTTP server listening on port $metricsPort")
        server.start(wait = true)
    }
}
