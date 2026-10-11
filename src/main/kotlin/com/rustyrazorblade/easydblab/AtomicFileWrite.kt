package com.rustyrazorblade.easydblab

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces this file's content with [text] in one step, so a concurrent reader sees either the old
 * content or the new content and never a partial file.
 *
 * The text goes to a unique temporary file in this file's own directory, which keeps the rename on
 * one file system, and that file is renamed over this one. Two writers never share a temporary file,
 * so the last rename wins. A failure leaves no temporary file behind.
 *
 * @param beforeRename runs on the staged file before the rename, for example to make it executable;
 *   an exception from it aborts the write
 */
fun File.writeTextAtomically(
    text: String,
    beforeRename: (File) -> Unit = {},
) {
    val directory = absoluteFile.parentFile
    directory.mkdirs()
    val staged = Files.createTempFile(directory.toPath(), ".$name.", ".tmp").toFile()
    try {
        staged.writeText(text)
        beforeRename(staged)
        Files.move(staged.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staged.delete()
    }
}
