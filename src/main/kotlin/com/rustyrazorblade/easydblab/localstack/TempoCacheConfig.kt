package com.rustyrazorblade.easydblab.localstack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tempo 3.0.3's top-level `cache` block, as typed data the local stack sets on its Tempo
 * ([TempoLocalConfig.live]). Each entry gives one cache backend and the roles it serves.
 */
@Serializable
internal data class TempoCacheConfig(
    val caches: List<TempoCache>,
)

/** One cache of [TempoCacheConfig]: which reads it keeps ([roles]) and the memcached that holds them. */
@Serializable
internal data class TempoCache(
    val roles: List<TempoCacheRole>,
    val memcached: TempoMemcachedClient,
)

/**
 * A Tempo cache role: the kind of backend read a cache keeps. Only the roles the local stack uses are
 * listed; Tempo has frontend and trace-id-index roles besides.
 */
@Serializable
internal enum class TempoCacheRole {
    /** A block's bloom filter shards, which every trace-by-id fetch reads for every block in its range. */
    @SerialName("bloom")
    BLOOM,

    /** A block's parquet footer. */
    @SerialName("parquet-footer")
    PARQUET_FOOTER,

    /** A block's parquet column index. */
    @SerialName("parquet-column-idx")
    PARQUET_COLUMN_INDEX,

    /** A block's parquet offset index. */
    @SerialName("parquet-offset-idx")
    PARQUET_OFFSET_INDEX,

    /** A block's parquet pages: the reads a trace-by-id fetch and a search spend their time on. */
    @SerialName("parquet-page")
    PARQUET_PAGE,
}

/** Tempo's memcached client: the server [addresses] and the client's limits. */
@Serializable
internal data class TempoMemcachedClient(
    val addresses: String,
    val timeout: String,
    @SerialName("max_idle_conns")
    val maxIdleConnections: Int,
    @SerialName("max_item_size")
    val maxItemSize: Int,
)
