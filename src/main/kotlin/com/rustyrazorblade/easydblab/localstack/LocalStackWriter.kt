package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.writeBytesAtomically
import com.rustyrazorblade.easydblab.writeTextAtomically
import java.io.File

/**
 * A stack directory the tool will not write into: it is not empty and holds no manifest of the tool,
 * so its files are someone else's; or it is an opened export, whose offline stack a live one must
 * not replace.
 *
 * @property directory the refused directory.
 */
class LocalStackDirectoryRefused(
    val directory: File,
    reason: String = "is not empty and was not created by observability local create",
) : IllegalStateException("$directory $reason; choose an empty or new directory")

/**
 * What one write of a stack did.
 *
 * @property written every owned file, by relative path, in the order written.
 * @property edited the owned files whose content on disk was not what the last write left there:
 *   the user edited them, and this write replaced the edit.
 * @property manifest the manifest this write recorded.
 * @property rewrite whether the directory already held a stack this tool wrote.
 */
data class LocalStackWriteResult(
    val written: List<String>,
    val edited: List<String>,
    val manifest: LocalStackManifest,
    val rewrite: Boolean,
)

/**
 * Writes a composed stack ([LocalStackComposer]) into its directory and records it in the manifest
 * ([LocalStackManifest]).
 *
 * The directory contract:
 * - a missing directory is created, and an empty one is used as it is;
 * - a directory that is not empty and holds no manifest is refused before anything is written;
 * - every owned file is replaced atomically, and the manifest is written last;
 * - an owned file the user edited is overwritten and reported;
 * - nothing else is opened, written or removed: not `compose.override.yml`, not `data/`, `cache/`
 *   or `grafana-data/`, not the dashboard tree. A file set that names one of them is refused.
 *
 * @property toolVersion the version recorded in the manifest.
 */
class LocalStackWriter(
    private val toolVersion: String,
) {
    /** Writes [files] (by path relative to [directory]) for [spec] into [directory]. */
    fun write(
        directory: File,
        spec: LocalStackSpec,
        files: Map<String, ByteArray>,
    ): LocalStackWriteResult {
        files.keys.forEach(::requireOwnable)
        val previous = refuseForeign(directory)
        directory.mkdirs()
        val manifestFile = File(directory, Constants.LocalStack.MANIFEST_FILE)

        val edited =
            files.keys.filter { path ->
                val recorded = previous?.files?.get(path)
                val onDisk = File(directory, path)
                recorded != null && onDisk.isFile && LocalStackManifest.sha256(onDisk.readBytes()) != recorded
            }
        files.forEach { (path, bytes) -> File(directory, path).writeBytesAtomically(bytes) }
        val manifest = LocalStackManifest.of(toolVersion, spec, files)
        manifestFile.writeTextAtomically(manifest.toJson())
        return LocalStackWriteResult(written = files.keys.toList(), edited = edited, manifest = manifest, rewrite = previous != null)
    }

    /**
     * The manifest of the stack in [directory], or null when there is none yet (the directory is
     * missing or empty). Throws [LocalStackDirectoryRefused] for anything else: a file, or a
     * directory with content and no manifest. It reads only the manifest and the directory listing.
     */
    fun refuseForeign(directory: File): LocalStackManifest? {
        val manifestFile = File(directory, Constants.LocalStack.MANIFEST_FILE)
        if (manifestFile.isFile) return LocalStackManifest.read(manifestFile)
        if (directory.exists() && (!directory.isDirectory || !directory.list().isNullOrEmpty())) {
            throw LocalStackDirectoryRefused(directory)
        }
        return null
    }

    private fun requireOwnable(path: String) {
        val segments = path.split('/')
        require(path.isNotEmpty() && !path.startsWith("/") && ".." !in segments && "" !in segments) {
            "$path is not a plain path inside the stack directory"
        }
        require(path != Constants.LocalStack.OVERRIDE_FILE && path != Constants.LocalStack.MANIFEST_FILE) {
            "$path is not a file the stack may own"
        }
        require(segments.first() !in Constants.LocalStack.PROTECTED_DIRS + Constants.LocalStack.DASHBOARDS_DIR) {
            "$path is under a directory the stack never writes"
        }
    }
}
