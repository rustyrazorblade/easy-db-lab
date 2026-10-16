package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.export.ExportTarget
import java.io.File
import java.util.UUID

/**
 * A query export's work directory, `<path>.work-<id>/` beside the zip: the reader's and the writer's
 * compose projects with their state, the staged metric blocks, and thanos's projects. It is kept after
 * the zip is written, and after a failure; the command prints where it is and how big, and nothing
 * removes it (owner decision D17).
 *
 * @property root the directory.
 * @property id its random part, which the temporary projects' names carry.
 */
class QueryWorkDirectory private constructor(
    val root: File,
    val id: String,
) {
    /** The reader's compose project. */
    val reader: File get() = File(root, "reader")

    /** The writer's compose project. */
    val writer: File get() = File(root, "writer")

    /** The writer's store: Loki's and Tempo's filesystem backends, the zip's logs and traces. */
    val writerStore: File get() = File(writer, ExportWriterStack.STORE_DIR)

    /** The writer Tempo's live-store WAL, which the drain lists. */
    val writerTempoWal: File get() =
        File(
            writer,
            "${ExportWriterStack.STATE_DIR}/${Constants.LocalStack.Services.TEMPO}/${ExportWriterStack.TEMPO_LIVE_WAL}",
        )

    /** The staged metric blocks, `mimir/<tenant>/` under it. */
    val staging: File get() = File(root, "metrics")

    /** thanos's compose projects. */
    val projects: File get() = File(root, "projects")

    /** The prefix of every temporary compose project's name. */
    val project: String get() = "edl-export-$id"

    /** The `docker compose logs` the reader and the writer saved before they were removed, those that exist. */
    fun stackLogs(): List<File> = listOf(reader, writer).map { File(it, Constants.Export.Query.STACK_LOG_FILE) }.filter { it.isFile }

    /** The bytes the directory holds. */
    fun size(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val ID_LENGTH = 8

        /** Makes the work directory of [target], with the writer's directories its containers write into. */
        fun create(
            target: ExportTarget,
            id: String = UUID.randomUUID().toString().take(ID_LENGTH),
        ): QueryWorkDirectory {
            val root = File(target.zip.absoluteFile.parentFile, "${target.zip.name}${Constants.Export.Query.WORK_INFIX}$id")
            check(root.mkdir()) { "Could not create the work directory $root" }
            val work = QueryWorkDirectory(root, id)
            // Made here, so they belong to the operator, as whom the writers run.
            listOf(
                work.reader,
                work.writerStore,
                File(work.writer, "${ExportWriterStack.STATE_DIR}/${Constants.LocalStack.Services.LOKI}"),
                File(work.writer, "${ExportWriterStack.STATE_DIR}/${Constants.LocalStack.Services.TEMPO}"),
                work.staging,
                work.projects,
            ).forEach { it.mkdirs() }
            return work
        }
    }
}
