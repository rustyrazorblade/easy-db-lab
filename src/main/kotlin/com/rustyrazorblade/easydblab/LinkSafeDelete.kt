package com.rustyrazorblade.easydblab

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes this file or directory tree without ever following a symbolic link. A path that does
 * not exist is already deleted.
 *
 * Kotlin's `File.deleteRecursively()` follows a directory link and deletes the link's target, so a
 * kit directory linked to a source checkout would take the checkout with it. Here a link, at the
 * top or anywhere below, is unlinked and its target is left untouched. Every recursive delete of a
 * workspace path goes through this function.
 *
 * @throws IOException naming this path when anything under it could not be deleted, so no caller
 *   reports a delete that did not happen
 */
fun File.deleteWithoutFollowingLinks() {
    val root = toPath()
    if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return
    try {
        Files.walkFileTree(root, UnlinkingVisitor)
    } catch (e: IOException) {
        throw IOException("Could not delete $absolutePath: ${e.message}", e)
    }
}

/**
 * [deleteWithoutFollowingLinks] as the cleanup after [primary] failed: a delete that fails too is
 * attached to [primary] as a suppressed exception, so the error the user sees is the one that
 * stopped the work, with the cleanup failure beside it.
 */
fun File.deleteAfterFailure(primary: Throwable) {
    try {
        deleteWithoutFollowingLinks()
    } catch (e: IOException) {
        primary.addSuppressed(e)
    }
}

/**
 * Deletes each entry the walk reaches. The walk does not follow links, so a link arrives as a
 * file and [Files.delete] removes the link itself.
 */
private object UnlinkingVisitor : SimpleFileVisitor<Path>() {
    override fun visitFile(
        file: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        Files.delete(file)
        return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(
        dir: Path,
        exc: IOException?,
    ): FileVisitResult {
        if (exc != null) throw exc
        Files.delete(dir)
        return FileVisitResult.CONTINUE
    }
}
