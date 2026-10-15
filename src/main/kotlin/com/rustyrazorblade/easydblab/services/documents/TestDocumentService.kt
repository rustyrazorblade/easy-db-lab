package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.services.ObjectStore
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * A test's documents index, `reports/<tenant>/<experiment>/index.html`, as `up` writes it. Uploads
 * go through [ReportArchiveService].
 */
interface TestDocumentService {
    /** Rewrites the index of [clusterState]'s test from every document in its folder; it says "No documents yet" when there are none. */
    fun rebuildIndex(clusterState: ClusterState): ClusterS3Path
}

/**
 * Default [TestDocumentService] over the [ObjectStore]. The index is uploaded from a local `.html`
 * file, so S3 stores it as `text/html`.
 */
class DefaultTestDocumentService(
    private val objectStore: ObjectStore,
) : TestDocumentService {
    override fun rebuildIndex(clusterState: ClusterState): ClusterS3Path {
        val store = ObservabilityStore.from(clusterState)
        val folder = store.documentsRoot(clusterState.experimentKey())
        val documents =
            objectStore
                .listFiles(folder, recursive = false)
                .map { it.path }
                .filter { it.getKey().endsWith(MARKDOWN_SUFFIX) }
                .map { path ->
                    val name = path.getKey().substringAfterLast('/')
                    RenderedDocument(name.removeSuffix(MARKDOWN_SUFFIX), MarkdownRenderer.toHtml(objectStore.readContent(path)))
                }
        val index = folder.resolve(INDEX_HTML)
        val scratch = createTempDirectory("easy-db-lab-documents").toFile()
        try {
            val page = File(scratch, INDEX_HTML).also { it.writeText(DocumentIndex.render(documents)) }
            objectStore.uploadFile(page, index, showProgress = false)
        } finally {
            scratch.deleteRecursively()
        }
        return index
    }

    private companion object {
        private const val MARKDOWN_SUFFIX = ".md"
        private const val INDEX_HTML = "index.html"
    }
}
