package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * An export's work directory, `<path>.work-<id>/` beside the zip, where the export keeps what it
 * builds locally before it goes into the zip: a tenant export's copy of Loki's index and its split, a
 * query export's temporary stacks and staged blocks. It is kept after the zip is written, and after a
 * failure; the command prints where it is and how big, and nothing removes it (owner decision D17).
 *
 * @property root the directory.
 * @property id its random part, which the export's temporary compose projects' names carry.
 */
class ExportWorkDirectory private constructor(
    val root: File,
    val id: String,
) {
    /** The bytes the directory holds. */
    fun size(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The `uid:gid` that owns the directory: the operator, who made it, as whom the export's containers run. */
    fun owner(): String = ownerOf(root)

    companion object {
        private const val ID_LENGTH = 8

        /** The `uid:gid` that owns [directory]. */
        fun ownerOf(directory: File): String {
            val path = directory.toPath()
            return "${Files.getAttribute(path, "unix:uid")}:${Files.getAttribute(path, "unix:gid")}"
        }

        /** A new random id for a work directory. */
        fun newId(): String = UUID.randomUUID().toString().take(ID_LENGTH)

        /** Makes the work directory of [target]; fails when it cannot, or when it already exists. */
        fun create(
            target: ExportTarget,
            id: String = newId(),
        ): ExportWorkDirectory {
            val root = File(target.zip.absoluteFile.parentFile, "${target.zip.name}${Constants.Export.WORK_INFIX}$id")
            check(root.mkdir()) { "Could not create the work directory $root" }
            return ExportWorkDirectory(root, id)
        }
    }
}
