package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.export.ExportTarget
import com.rustyrazorblade.easydblab.export.ExportWorkDirectory
import java.io.File

/**
 * A query export's work directory ([ExportWorkDirectory], `<path>.work-<id>/` beside the zip, kept): the
 * reader's and the writer's compose projects with their state, the staged metric blocks, and thanos's
 * projects.
 *
 * @property work the kept directory.
 */
class QueryWorkDirectory private constructor(
    private val work: ExportWorkDirectory,
) {
    /** The directory. */
    val root: File get() = work.root

    /** Its random part, which the temporary projects' names carry. */
    val id: String get() = work.id

    /** The operator's `uid:gid`, as whom the writers and thanos run. */
    fun owner(): String = work.owner()

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
    fun size(): Long = work.size()

    companion object {
        /** Makes the work directory of [target], with the writer's directories its containers write into. */
        fun create(
            target: ExportTarget,
            id: String = ExportWorkDirectory.newId(),
        ): QueryWorkDirectory {
            val work = QueryWorkDirectory(ExportWorkDirectory.create(target, id))
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
