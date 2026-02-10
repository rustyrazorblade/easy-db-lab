package com.rustyrazorblade.cassandrametrics.collector

import com.datastax.oss.driver.api.core.CqlSession
import com.rustyrazorblade.cassandrametrics.cache.MetricsCache
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

class MetricsCollector(
    private val session: CqlSession,
    private val cache: MetricsCache,
    private val registry: VirtualTableRegistry,
    private val pollIntervalSeconds: Long,
    threadPoolSize: Int,
    includeSystemKeyspaces: Boolean,
) {
    private val executor = Executors.newFixedThreadPool(threadPoolSize)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val queryExecutor = CqlQueryExecutor(session, includeSystemKeyspaces)

    fun start() {
        log.info { "Starting metrics collection for ${registry.tables.size} virtual tables" }
        scheduler.scheduleAtFixedRate(
            ::collectAll,
            0,
            pollIntervalSeconds,
            TimeUnit.SECONDS,
        )
    }

    fun stop() {
        log.info { "Stopping metrics collector" }
        scheduler.shutdown()
        executor.shutdown()
        scheduler.awaitTermination(pollIntervalSeconds * 2, TimeUnit.SECONDS)
        executor.awaitTermination(pollIntervalSeconds * 2, TimeUnit.SECONDS)
    }

    private fun collectAll() {
        val futures =
            registry.tables.map { table ->
                executor.submit {
                    collectTable(table)
                }
            }

        for (future in futures) {
            try {
                future.get(pollIntervalSeconds * 2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                log.warn { "Collection task timed out or failed: ${e.message}" }
            }
        }
    }

    private fun collectTable(table: VirtualTableDefinition) {
        try {
            val prometheusText = queryExecutor.collect(table)
            cache.update(table.tableName, prometheusText)
        } catch (e: Exception) {
            log.warn { "Failed to collect ${table.tableName}: ${e.message}" }
        }
    }
}
