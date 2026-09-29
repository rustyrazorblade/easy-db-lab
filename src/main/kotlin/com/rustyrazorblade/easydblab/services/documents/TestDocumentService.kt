package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.services.ObjectStore
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * `report upload` found files that break the document rule; nothing was uploaded.
 *
 * @property rejected Every refused file and why.
 */
class DocumentsRejectedException(
    val rejected: List<RejectedDocument>,
) : IllegalArgumentException(
        "Nothing was uploaded. Rejected: " + rejected.joinToString("; ") { "${it.name} ${it.reason}" },
    )

/**
 * An upload stopped at [failed]. The documents in [stored] were uploaded before it, with their HTML
 * copies, but the index was not rebuilt, so they are in S3 and not yet in the index; upload again.
 */
class DocumentUploadFailedException(
    val failed: String,
    val stored: List<String>,
    cause: Throwable,
) : IllegalStateException(
        "Uploading $failed failed: ${cause.message}. " +
            (if (stored.isEmpty()) "No document was stored." else "Already stored, not yet in the index: ${stored.joinToString(", ")}.") +
            " Run report upload again.",
        cause,
    )

/**
 * The documents of one upload.
 *
 * @property documents Each uploaded markdown file's name and S3 path.
 * @property index The test's rebuilt index.
 */
data class UploadedDocuments(
    val documents: List<Pair<String, ClusterS3Path>>,
    val index: ClusterS3Path,
)

/**
 * A test's documents: the operator's markdown files in the test's folder in the account bucket,
 * `reports/<tenant>/<name>-<id>/`, each with an HTML copy, and one `index.html` that holds them all
 * for Grafana. It uses the operator's own credentials, so it works before and after `down`.
 */
interface TestDocumentService {
    /**
     * Stores [files] in the test's folder of [clusterState], each with its HTML copy, and rebuilds the
     * index. A file with the name of a stored document replaces it.
     *
     * @throws DocumentsRejectedException, before anything is uploaded, if any file breaks the rule.
     */
    fun upload(
        clusterState: ClusterState,
        files: List<File>,
    ): UploadedDocuments

    /** Rewrites the index of [clusterState]'s test from every document in its folder; it says "No documents yet" when there are none. */
    fun rebuildIndex(clusterState: ClusterState): ClusterS3Path
}

/**
 * Default [TestDocumentService] over the [ObjectStore]. Every HTML page is uploaded from a local
 * `.html` file, so S3 stores it as `text/html`.
 */
class DefaultTestDocumentService(
    private val objectStore: ObjectStore,
) : TestDocumentService {
    override fun upload(
        clusterState: ClusterState,
        files: List<File>,
    ): UploadedDocuments {
        val rejected = DocumentNames.rejected(files)
        if (rejected.isNotEmpty()) throw DocumentsRejectedException(rejected)

        val store = ObservabilityStore.from(clusterState)
        val cluster = clusterState.clusterLabelName()
        val uploaded =
            withScratch { scratch ->
                val stored = mutableListOf<Pair<String, ClusterS3Path>>()
                files.forEach { file ->
                    runCatching {
                        val markdown = store.document(cluster, file.name)
                        objectStore.uploadFile(file, markdown, showProgress = false)
                        val page = File(scratch, DocumentNames.htmlName(file.name))
                        page.writeText(DocumentIndex.page(DocumentNames.stem(file.name), MarkdownRenderer.toHtml(file.readText())))
                        objectStore.uploadFile(page, store.document(cluster, page.name), showProgress = false)
                        stored += file.name to markdown
                    }.getOrElse { e -> throw DocumentUploadFailedException(file.name, stored.map { it.first }, e) }
                }
                stored.toList()
            }
        return UploadedDocuments(uploaded, rebuildIndex(clusterState))
    }

    override fun rebuildIndex(clusterState: ClusterState): ClusterS3Path {
        val store = ObservabilityStore.from(clusterState)
        val cluster = clusterState.clusterLabelName()
        val documents =
            objectStore
                .listFiles(store.documentsRoot(cluster), recursive = false)
                .map { it.path }
                .filter { it.getKey().endsWith(DocumentNames.MARKDOWN_SUFFIX) }
                .map { path ->
                    val name = path.getKey().substringAfterLast('/')
                    RenderedDocument(DocumentNames.stem(name), MarkdownRenderer.toHtml(objectStore.readContent(path)))
                }
        val index = store.document(cluster, DocumentNames.INDEX_HTML)
        withScratch { scratch ->
            val page = File(scratch, DocumentNames.INDEX_HTML).also { it.writeText(DocumentIndex.render(documents)) }
            objectStore.uploadFile(page, index, showProgress = false)
        }
        return index
    }

    private fun <T> withScratch(block: (File) -> T): T {
        val scratch = createTempDirectory("easy-db-lab-documents").toFile()
        try {
            return block(scratch)
        } finally {
            scratch.deleteRecursively()
        }
    }
}
