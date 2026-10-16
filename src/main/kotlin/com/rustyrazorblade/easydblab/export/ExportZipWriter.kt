package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Where an export is written: a new `.zip` file in a directory that exists. The zip holds one
 * directory named after it, so unzipping `lab.zip` makes `lab/`, the stack directory.
 *
 * @property zip the zip's path.
 */
class ExportTarget private constructor(
    val zip: File,
) {
    /** The directory every entry sits in: the zip's name without `.zip`. */
    val root: String get() = zip.name.removeSuffix(Constants.Export.ZIP_EXTENSION)

    override fun toString(): String = zip.path

    companion object {
        /**
         * Checks [path]: it must end in `.zip`, name no existing file, and sit in a directory that exists.
         *
         * @throws IllegalArgumentException naming the broken rule; nothing is created or changed.
         */
        fun of(path: File): ExportTarget {
            require(path.name.endsWith(Constants.Export.ZIP_EXTENSION) && path.name != Constants.Export.ZIP_EXTENSION) {
                "The export path $path must name a ${Constants.Export.ZIP_EXTENSION} file"
            }
            require(!path.exists()) { "$path already exists; an export never overwrites a file. Choose a new path." }
            val parent = path.absoluteFile.parentFile
            require(parent.isDirectory) { "The directory of the export path, $parent, does not exist" }
            return ExportTarget(path)
        }
    }
}

/** Takes the entries of a zip being written, each under the stack directory. */
fun interface ExportZipSink {
    /**
     * Streams [input] to the entry [path], relative to the stack directory, and closes [input].
     *
     * @return the bytes copied.
     * @throws IllegalArgumentException when [path] is not a plain relative path inside the stack.
     */
    fun put(
        path: String,
        input: InputStream,
    ): Long
}

/**
 * Writes an export's zip atomically.
 *
 * The zip streams to `<path>.partial-<random>` beside the path, so no object is held in memory and
 * the final move stays on one file system; Zip64 records are written as soon as the zip passes 4 GiB
 * or 65,535 entries. Only a complete zip is moved to the path, and never over a file: the move is a
 * hard link, which fails when the path exists, then the removal of the partial name (a plain move
 * that refuses an existing path where the file system has no hard links). On any failure the partial
 * file, the tool's own scratch, is removed and nothing is left at the path.
 *
 * @property partialSuffix makes the random part of the partial file's name.
 */
class ExportZipWriter(
    private val partialSuffix: () -> String = { UUID.randomUUID().toString() },
) {
    /** Writes the zip of [target] with [body], which puts every entry; returns what [body] returns. */
    fun <T> write(
        target: ExportTarget,
        body: (ExportZipSink) -> T,
    ): T {
        val partial = File(target.zip.absoluteFile.parentFile, "${target.zip.name}${Constants.Export.PARTIAL_INFIX}${partialSuffix()}")
        try {
            // A failure inside the body names its own step; one of the zip file itself is the stack's.
            val result =
                exportStep(ExportSignal.STACK, "write ${partial.path}") {
                    ZipOutputStream(Files.newOutputStream(partial.toPath()).buffered(Constants.Export.COPY_BUFFER_BYTES)).use { zip ->
                        body(StreamingSink(zip, target.root))
                    }
                }
            exportStep(ExportSignal.STACK, "move ${partial.name} to ${target.zip.path}") { moveWithoutOverwrite(partial, target.zip) }
            return result
        } finally {
            Files.deleteIfExists(partial.toPath())
        }
    }

    private fun moveWithoutOverwrite(
        partial: File,
        zip: File,
    ) {
        val linked =
            try {
                Files.createLink(zip.toPath(), partial.toPath())
                true
            } catch (e: FileAlreadyExistsException) {
                // A path that appeared meanwhile is someone else's file: never replaced.
                throw IOException("$zip appeared while the export was written; it was left as it is", e)
            } catch (_: FileSystemException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }
        // No hard links here: a plain move, which refuses an existing path too.
        if (!linked) Files.move(partial.toPath(), zip.toPath())
    }

    /** Streams each entry into [zip] under [root]. */
    private class StreamingSink(
        private val zip: ZipOutputStream,
        private val root: String,
    ) : ExportZipSink {
        override fun put(
            path: String,
            input: InputStream,
        ): Long {
            val segments = path.split('/')
            require(path.isNotEmpty() && !path.startsWith("/") && segments.none { it.isEmpty() || it == "." || it == ".." }) {
                "$path is not a plain path inside the export"
            }
            zip.putNextEntry(ZipEntry("$root/$path"))
            val copied = input.use { it.copyTo(zip, Constants.Export.COPY_BUFFER_BYTES) }
            zip.closeEntry()
            return copied
        }
    }
}
