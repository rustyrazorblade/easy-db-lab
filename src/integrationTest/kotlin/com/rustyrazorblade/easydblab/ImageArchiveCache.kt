package com.rustyrazorblade.easydblab

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where [ImageArchiveCache] gets an image's content identity and its docker-load archive. */
interface ImageArchiveSource {
    /** An ID of what [image] names right now, such as a digest, which changes whenever its content does. */
    fun contentId(image: String): String

    /** Writes a docker-load archive of [image] to [target]. */
    fun save(
        image: String,
        target: Path,
    )
}

/**
 * Container image archives fetched once and shared by every test JVM of a run.
 *
 * The integration tier runs in several forked JVMs, and each starts its own K3s containers seeded
 * with the same images. This cache keeps one archive per image reference and content ID in
 * [directory], so an image is fetched at most once instead of once per fork and container, and a
 * changed image (a new digest behind the same tag) is fetched again rather than served stale.
 *
 * Every lookup and save runs under an exclusive lock on [LOCK_FILE] in [directory], so no two
 * processes sharing the directory ever save at the same time. A file lock is held per JVM, not per
 * thread, so threads of one JVM queue on an in-memory lock for the same directory first. An
 * archive is written under a temporary name and renamed into place, so it is never seen half
 * written.
 */
class ImageArchiveCache(
    private val directory: Path,
    private val source: ImageArchiveSource,
) {
    /** The archive of [image], saved from [source] if this cache does not hold it yet. */
    fun archive(image: String): Path {
        Files.createDirectories(directory)
        return exclusively {
            val archive = directory.resolve(archiveName(image, source.contentId(image)))
            if (!Files.exists(archive)) save(image, archive)
            archive
        }
    }

    private fun save(
        image: String,
        archive: Path,
    ) {
        val partial = Files.createTempFile(directory, PARTIAL_PREFIX, PARTIAL_SUFFIX)
        try {
            source.save(image, partial)
            Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    /** Runs [action] holding this JVM's lock on [directory] and then the cross-process file lock. */
    private fun <T> exclusively(action: () -> T): T {
        val lockFile = directory.resolve(LOCK_FILE).toAbsolutePath().normalize()
        return jvmLocks.computeIfAbsent(lockFile) { ReentrantLock() }.withLock {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { action() }
            }
        }
    }

    private fun archiveName(
        image: String,
        contentId: String,
    ): String = "${image.replace(UNSAFE_FILE_CHARACTERS, "_")}-${contentId.substringAfter(':')}$ARCHIVE_SUFFIX"

    private companion object {
        /** The file every process sharing a cache directory locks before it looks up or saves. */
        const val LOCK_FILE = ".lock"
        const val ARCHIVE_SUFFIX = ".tar"
        const val PARTIAL_PREFIX = ".partial-"
        const val PARTIAL_SUFFIX = ".tmp"
        val UNSAFE_FILE_CHARACTERS = Regex("[^A-Za-z0-9._-]")

        /** One lock per lock file for this JVM, which the file lock alone cannot serialize. */
        val jvmLocks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
