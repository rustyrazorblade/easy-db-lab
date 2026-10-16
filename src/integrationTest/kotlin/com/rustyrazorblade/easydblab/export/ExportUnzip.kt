package com.rustyrazorblade.easydblab.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Opens an export's zip the way an operator does, with `unzip`: every entry under [parent],
 * directory entries included, so a directory the zip holds with nothing in it (such as `data/tempo/`
 * of an export with no traces) exists after unpacking, as it does after `unzip`.
 */
object ExportUnzip {
    /** Unpacks [zip] into [parent]. */
    fun unpack(
        zip: File,
        parent: File,
    ) {
        ZipFile(zip).use { file -> file.entries().asSequence().forEach { entry -> write(file, entry, File(parent, entry.name)) } }
    }

    private fun write(
        file: ZipFile,
        entry: ZipEntry,
        target: File,
    ) {
        if (entry.isDirectory) {
            target.mkdirs()
            return
        }
        target.parentFile.mkdirs()
        file.getInputStream(entry).use { input -> target.writeBytes(input.readBytes()) }
    }
}
