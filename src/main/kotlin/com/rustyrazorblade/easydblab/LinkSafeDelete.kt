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
 * Deletes this file or directory tree without ever following a symbolic link, and reports
 * whether nothing is left at this path.
 *
 * Kotlin's `File.deleteRecursively()` follows a directory link and deletes the link's target, so a
 * kit directory linked to a source checkout would take the checkout with it. Here a link, at the
 * top or anywhere below, is unlinked and its target is left untouched. Every recursive delete of a
 * workspace path goes through this function.
 */
fun File.deleteWithoutFollowingLinks(): Boolean {
    val root = toPath()
    if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return true
    return runCatching { Files.walkFileTree(root, UnlinkingVisitor) }.isSuccess
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
