package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.time.Instant

/**
 * Copies one tenant's Loki index into a tenant export, with nothing of another tenant in it (owner
 * decision 22).
 *
 * A table's files are its shared multi-tenant files at the root, which hold every tenant an ingester
 * received, and the tenant's own `<table>/<tenant>/` files. Each table is read into a local copy under
 * the work directory, table by table, and read again from a fresh listing when a listed file is gone
 * (the account compactor replaced the table meanwhile), so the copy never holds half of one listing.
 * When any shared file was copied, [split] (Loki's own compactor) splits them per tenant; a shared
 * file left after it fails the copy. Then only the tenant's own files go into the zip. The local copy is
 * kept with the work directory.
 *
 * @property store the account bucket, only listed and read.
 * @property split splits the shared files of the local copy.
 */
internal class LokiIndexCopy(
    private val store: ExportStore,
    private val split: LokiIndexSplit,
) {
    companion object {
        private val INDEX = "${Constants.Observability.LOGS_ROOT}/index"

        /** The local copy of the index, a filesystem store, in the work directory. */
        private const val STORE_DIR = "store"

        /** The split's own directory, in the work directory. */
        private const val SPLIT_DIR = "split"

        /** The logs the split saved in its directory under [work], those that exist. */
        fun savedLogs(work: File): List<File> =
            listOf(File(work, "$SPLIT_DIR/${Constants.Export.Query.STACK_LOG_FILE}")).filter { it.isFile }
    }

    /** The files of [table] a copy for [tenant] reads: the shared files at its root and the tenant's own directory. */
    fun files(
        table: String,
        tenant: String,
    ): List<StoreObject> =
        store.list(table).filter { obj ->
            val rest = obj.key.removePrefix(table)
            !rest.contains('/') || rest.startsWith("$tenant/")
        }

    /** Copies [tenant]'s index of [tables] (each `loki/index/<table>/`) into [zip], with [work] as the local copy's directory. */
    fun copy(
        tables: List<String>,
        tenant: String,
        zip: ExportRecorder,
        work: File,
    ) {
        val local = File(work, STORE_DIR)
        tables.forEach { table -> exportStep(ExportSignal.LOGS, "copy index table $table") { copyTable(table, tenant, local) } }
        val index = File(local, INDEX)
        if (sharedFiles(local).isNotEmpty()) {
            exportStep(ExportSignal.LOGS, "split Loki's shared index files with Loki's compactor in $work") {
                split.split(local, File(work, SPLIT_DIR))
            }
            exportStep(ExportSignal.LOGS, "check that Loki's compactor split every shared index file in $local") {
                val left = sharedFiles(local)
                check(left.isEmpty()) { "Loki's compactor left shared index files: ${left.joinToString(", ")}" }
            }
        }
        index.listFiles().orEmpty().filter { it.isDirectory }.sorted().forEach { table ->
            val own = File(table, tenant)
            own.walkTopDown().filter { it.isFile }.sorted().forEach { file ->
                val key = "$INDEX/${table.name}/$tenant/${file.relativeTo(own).invariantSeparatorsPath}"
                exportStep(ExportSignal.LOGS, "put $key") {
                    zip.put("${Constants.Export.DATA_DIR}/$key", file.inputStream(), Instant.ofEpochMilli(file.lastModified()))
                }
            }
        }
    }

    /** The shared files at the root of every table of the local copy, relative to it. */
    private fun sharedFiles(local: File): List<String> =
        File(local, INDEX)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .flatMap { table -> table.listFiles().orEmpty().filter { it.isFile } }
            .map { it.relativeTo(local).invariantSeparatorsPath }
            .sorted()

    /** Reads every file of [table] for [tenant] into [local], again from a fresh listing when one is gone. */
    private fun copyTable(
        table: String,
        tenant: String,
        local: File,
    ) {
        val directory = inside(local, table)
        val read =
            (1..Constants.Export.LOKI_TABLE_ATTEMPTS).any { _ ->
                directory.deleteRecursively()
                readAll(files(table, tenant), local)
            }
        check(read) { "$table was replaced while it was read, ${Constants.Export.LOKI_TABLE_ATTEMPTS} times" }
    }

    /** Reads every object of [listing] to its key under [local]; false when one of them is gone. */
    private fun readAll(
        listing: List<StoreObject>,
        local: File,
    ): Boolean =
        listing.all { obj ->
            val input = store.open(obj.key) ?: return@all false
            val file = inside(local, obj.key).apply { parentFile.mkdirs() }
            input.use { source -> file.outputStream().use { source.copyTo(it, Constants.Export.COPY_BUFFER_BYTES) } }
            true
        }

    /** [key] under [local]; a key that would leave it is refused. */
    private fun inside(
        local: File,
        key: String,
    ): File {
        val file = File(local, key).normalize()
        require(file.toPath().startsWith(local.normalize().toPath())) { "$key leaves the local copy of the index" }
        return file
    }
}
