package com.rustyrazorblade.easydblab

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * One JVM competing for an [ImageArchiveCache] entry, run as a separate process by
 * [ImageArchiveCacheIntegrationTest] so the cache's cross-process lock is exercised the way the
 * parallel Gradle test forks exercise it.
 *
 * Its source records every save in a shared log and holds an in-flight marker file for the length
 * of the save, failing if another process already holds it, so an overlapping save ends the process
 * with an error. It waits for a start file before asking, so every contender asks at about the same
 * moment, and prints the SHA-256 of the archive it was given.
 */
object ImageArchiveCacheContender {
    private const val START_POLL_MILLIS = 10L
    private const val SAVE_MILLIS = 300L
    private const val ARG_COUNT = 6

    /** Arguments: cache directory, start file, save log, in-flight marker, image, content ID. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == ARG_COUNT) { "expected $ARG_COUNT arguments, got ${args.size}" }
        val source = MarkingSource(saveLog = Path.of(args[2]), inFlight = Path.of(args[3]), id = args[5])
        val start = Path.of(args[1])
        while (!Files.exists(start)) Thread.sleep(START_POLL_MILLIS)

        val archive = ImageArchiveCache(Path.of(args[0]), source).archive(args[4])

        println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))))
    }

    /** A source whose saves leave evidence on disk that another process can see. */
    private class MarkingSource(
        private val saveLog: Path,
        private val inFlight: Path,
        private val id: String,
    ) : ImageArchiveSource {
        override fun contentId(image: String): String = id

        override fun save(
            image: String,
            target: Path,
        ) {
            // createFile fails when the marker exists: that is another process saving right now.
            Files.createFile(inFlight)
            Files.writeString(saveLog, "${ProcessHandle.current().pid()}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Thread.sleep(SAVE_MILLIS)
            Files.writeString(target, "archive of $image $id")
            Files.delete(inFlight)
        }
    }
}
