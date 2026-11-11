package com.rustyrazorblade.easydblab.commands.report

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.documents.TestDocumentService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Stores markdown files in the current test's document folder, `reports/<tenant>/<name>-<id>/`, and
 * rebuilds the test's index, which the Tests and comparison dashboards show.
 *
 * It needs only the workspace's `state.json` and the operator's AWS credentials, not the cluster, so
 * it works before and after `down`. Every file is checked before any is uploaded; see
 * [com.rustyrazorblade.easydblab.services.documents.DocumentNames] for the rule.
 */
@RequireProfileSetup
@Command(
    name = "upload",
    description = ["Upload markdown files to the current test's documents"],
    mixinStandardHelpOptions = true,
)
class ReportUpload : PicoBaseCommand() {
    @Parameters(arity = "1..*", paramLabel = "FILE", description = ["Markdown files (.md) named with letters, digits, '.', '_' and '-'"])
    var files: List<File> = emptyList()

    private val documentService: TestDocumentService by inject()

    override fun execute() {
        val uploaded = documentService.upload(clusterState, files)
        eventBus.emit(
            Event.Report.DocumentsUploaded(
                documents = uploaded.documents.map { (name, path) -> Event.Report.StoredDocument(name, path.toUri()) },
                indexUri = uploaded.index.toUri(),
            ),
        )
    }
}
