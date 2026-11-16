package com.rustyrazorblade.easydblab.server

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/**
 * Background cache for environment status data.
 *
 * Periodically asks [ClusterStatusCollector] for the live status (AWS, SSH, Kubernetes) and
 * stores the result in memory for instant retrieval via the /status endpoint. It only schedules
 * and caches: what the status contains is the collector's.
 */
class StatusCache(
    private val refreshIntervalSeconds: Long = Constants.Time.DEFAULT_STATUS_REFRESH_SECONDS,
    private val autoShutdown: Boolean = false,
    private val vpcName: String? = null,
    private val onShutdown: () -> Unit = { exitProcess(0) },
) : KoinComponent,
    CachedStatus {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val INITIALIZATION_TIMEOUT_SECONDS = 60L

        val VALID_SECTIONS =
            listOf(
                "cluster",
                "nodes",
                "networking",
                "securityGroup",
                "spark",
                "opensearch",
                "s3",
                "kubernetes",
                "stressJobs",
                "cassandraVersion",
                "accessInfo",
            )
    }

    private val eventBus: EventBus by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val vpcService: VpcService by inject()
    private val collector: ClusterStatusCollector by inject()

    private val lock = ReentrantLock()
    private val timer = Timer("status-cache-refresh", true)
    private val initializedLatch = CountDownLatch(1)

    @Volatile
    private var cachedResponse: StatusResponse? = null

    private val json = Json { explicitNulls = false }

    /**
     * Start the background refresh cycle.
     * Performs an immediate fetch, then schedules recurring refreshes.
     */
    fun start() {
        log.info { "Starting status cache with ${refreshIntervalSeconds}s refresh interval" }
        refresh()
        scheduleNextRefresh()
    }

    /**
     * Stop the background refresh timer.
     */
    fun stop() {
        timer.cancel()
        log.info { "Status cache stopped" }
    }

    /**
     * Force an immediate refresh, resetting the timer.
     * Blocks until the refresh completes.
     */
    override fun forceRefresh() {
        timer.purge()
        refresh()
        scheduleNextRefresh()
    }

    /**
     * Get the cached status as a JSON string.
     * Blocks until the cache has been populated at least once (up to 60 seconds).
     *
     * @param section Optional section name to return only that section
     * @return JSON string, or null if the cache could not be populated within the timeout
     * @throws IllegalArgumentException if the section name is invalid
     */
    override fun getStatus(section: String?): String? {
        initializedLatch.await(INITIALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val response = cachedResponse ?: return null
        if (section == null) {
            return json.encodeToString(StatusResponse.serializer(), response)
        }
        return serializeSection(response, section)
    }

    private val sectionSerializers: Map<String, (StatusResponse) -> String> =
        mapOf(
            "cluster" to { r -> json.encodeToString(ClusterInfo.serializer(), r.cluster) },
            "nodes" to { r -> json.encodeToString(NodesInfo.serializer(), r.nodes) },
            "networking" to { r -> serializeNullable(NetworkingInfo.serializer(), r.networking) },
            "securityGroup" to { r -> serializeNullable(SecurityGroupInfo.serializer(), r.securityGroup) },
            "spark" to { r -> serializeNullable(SparkInfo.serializer(), r.spark) },
            "opensearch" to { r -> serializeNullable(OpenSearchInfo.serializer(), r.opensearch) },
            "s3" to { r -> serializeNullable(S3Info.serializer(), r.s3) },
            "kubernetes" to { r -> serializeNullable(KubernetesInfo.serializer(), r.kubernetes) },
            "stressJobs" to { r -> serializeNullable(StressInfo.serializer(), r.stressJobs) },
            "cassandraVersion" to { r -> serializeNullable(CassandraVersionInfo.serializer(), r.cassandraVersion) },
            "accessInfo" to { r -> serializeNullable(AccessInfo.serializer(), r.accessInfo) },
        )

    private fun serializeSection(
        response: StatusResponse,
        section: String,
    ): String {
        val serializer =
            sectionSerializers[section]
                ?: throw IllegalArgumentException(
                    "Invalid section: '$section'. Valid sections: ${VALID_SECTIONS.joinToString(", ")}",
                )
        return serializer(response)
    }

    private fun <T> serializeNullable(
        serializer: KSerializer<T>,
        value: T?,
    ): String = value?.let { json.encodeToString(serializer, it) } ?: "null"

    private fun scheduleNextRefresh() {
        timer.purge()
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    refresh()
                    scheduleNextRefresh()
                }
            },
            refreshIntervalSeconds * Constants.Time.MILLIS_PER_SECOND,
        )
    }

    private fun refresh() {
        lock.withLock {
            log.debug { "Refreshing status cache" }
            try {
                if (!clusterStateManager.exists()) {
                    log.debug { "No cluster state found, skipping refresh" }
                    cachedResponse = null
                    initializedLatch.countDown()
                    return
                }

                val clusterState = clusterStateManager.load()
                cachedResponse = collector.collect(clusterState).response
                initializedLatch.countDown()
                log.debug { "Status cache refreshed" }
            } catch (e: Exception) {
                log.warn(e) { "Failed to refresh status cache" }
            }
            checkVpcExists()
        }
    }

    private fun checkVpcExists() {
        if (!autoShutdown) return
        if (vpcName.isNullOrBlank()) {
            log.warn { "Auto-shutdown enabled but no VPC name available; skipping infrastructure check" }
            return
        }
        try {
            val found = vpcService.findVpcByName(vpcName)
            if (found == null) {
                log.warn { "VPC '$vpcName' not found — infrastructure is gone" }
                eventBus.emit(Event.Server.InfrastructureGone(vpcName))
                onShutdown()
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to check VPC existence for '$vpcName'; skipping shutdown" }
        }
    }
}
