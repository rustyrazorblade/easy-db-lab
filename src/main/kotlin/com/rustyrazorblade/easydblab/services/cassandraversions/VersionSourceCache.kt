package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.writeTextAtomically
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * A cached source list.
 *
 * @property stale true when a refresh failed and this is the older list from the file
 */
data class Cached<T>(
    val value: T,
    val fetchedAt: Instant,
    val stale: Boolean,
)

/** A source list that could not be fetched and has no cached copy. */
class VersionSourceUnavailableException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * The on-disk form of one cached list: the list and when it was fetched, so a read can tell its age
 * and a failed refresh can say how stale the copy it falls back to is.
 */
@Serializable
private data class CacheFile<T>(
    val fetchedAtEpochMillis: Long,
    val value: T,
)

/**
 * One source list (the ASF releases or the cassandra-builds builds) cached in a profile file, so
 * `cassandra list` and the resolver call the public source only when there is no file or on
 * `--refresh`. A refresh that fails falls back to the file and marks it stale.
 *
 * @property sourceName how the source is named in an error
 * @property url the URL the fetch reads, named in an error
 */
class VersionSourceCache<T>(
    private val file: File,
    private val sourceName: String,
    private val url: String,
    serializer: KSerializer<T>,
    private val clock: () -> Instant = Instant::now,
) {
    private val fileSerializer = CacheFile.serializer(serializer)

    /**
     * The cached list, or a fetched one when there is no usable file or [refresh] is set.
     *
     * @throws VersionSourceUnavailableException when the fetch fails and there is no file
     */
    fun get(
        refresh: Boolean = false,
        fetch: () -> T,
    ): Cached<T> {
        val onDisk = read()
        if (onDisk != null && !refresh) return onDisk
        return try {
            val value = fetch()
            val fetchedAt = clock()
            file.writeTextAtomically(JSON.encodeToString(fileSerializer, CacheFile(fetchedAt.toEpochMilli(), value)))
            Cached(value, fetchedAt, stale = false)
        } catch (e: IOException) {
            onDisk?.copy(stale = true) ?: throw unavailable(e)
        } catch (e: RuntimeException) {
            onDisk?.copy(stale = true) ?: throw unavailable(e)
        }
    }

    private fun unavailable(cause: Throwable) =
        VersionSourceUnavailableException("Cannot read the $sourceName list from $url: ${cause.message}", cause)

    private fun read(): Cached<T>? {
        if (!file.isFile) return null
        return try {
            val stored = JSON.decodeFromString(fileSerializer, file.readText())
            Cached(stored.value, Instant.ofEpochMilli(stored.fetchedAtEpochMillis), stale = false)
        } catch (e: SerializationException) {
            log.warn(e) { "Ignoring the unreadable cache file $file" }
            null
        } catch (e: IllegalArgumentException) {
            log.warn(e) { "Ignoring the unreadable cache file $file" }
            null
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
