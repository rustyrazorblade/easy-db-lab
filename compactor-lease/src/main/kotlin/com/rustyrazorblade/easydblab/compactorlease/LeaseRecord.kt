package com.rustyrazorblade.easydblab.compactorlease

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * The body of the account compactor's lease, `leases/compactor.json`.
 *
 * No lease decision reads [renewedAt]: each process judges the lease only by its version and its
 * own monotonic clock, so two nodes' clocks are never compared. [renewedAt] is for
 * `observability compactor status`. [sequence] goes up on every write, so a renewal always changes
 * the content, and with it the ETag.
 *
 * @property holder the holding pod: the cluster label, then `/`, then the pod name.
 * @property sequence how many times the lease was written before this write.
 * @property ttlSeconds the TTL the holder ran with.
 * @property renewedAt the holder's wall-clock time when it wrote this record, as ISO-8601 text.
 * @throws IllegalArgumentException when [holder] is blank or [sequence] or [ttlSeconds] is negative.
 */
@Serializable
data class LeaseRecord(
    val holder: String,
    val sequence: Long,
    val ttlSeconds: Long,
    val renewedAt: Instant,
) {
    init {
        require(holder.isNotBlank()) { "A lease holder must not be blank" }
        require(sequence >= 0) { "A lease sequence must not be negative" }
        require(ttlSeconds >= 0) { "A lease TTL must not be negative" }
    }

    /**
     * The record that follows this one, written by [holder] at [renewedAt]. It names the writer's
     * own TTL, [ttlSeconds], not the one the previous writer ran with.
     */
    fun next(
        holder: String,
        ttlSeconds: Long,
        renewedAt: Instant,
    ): LeaseRecord = LeaseRecord(holder, sequence + 1, ttlSeconds, renewedAt)

    /** This record as the JSON body of the lease object. */
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The object key of the account compactor's lease within the account bucket. The store's
         * leases sit under `leases/`, outside every backend's root: under `mimir/`, Mimir would read
         * a lease as a tenant.
         */
        const val KEY = "leases/compactor.json"

        /**
         * Reads a lease body.
         *
         * @throws kotlinx.serialization.SerializationException when [body] is not a lease.
         */
        fun fromJson(body: String): LeaseRecord = json.decodeFromString(serializer(), body)
    }
}
