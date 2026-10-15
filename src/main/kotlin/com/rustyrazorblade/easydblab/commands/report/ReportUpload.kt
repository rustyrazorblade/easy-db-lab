package com.rustyrazorblade.easydblab.commands.report

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.documents.ReportArchiveService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.Unmatched
import java.io.File

/**
 * Stores one directory as a report of the current experiment: zipped, kept as
 * `reports/<tenant>/<experiment>/report-<yyyyMMdd-HHmmss>.zip` (UTC), unpacked beside the zip, and
 * shown by the documents panels of every cluster of the experiment.
 *
 * The experiment and the tenant come from the workspace's `state.json`; there is no option for
 * them. It needs only that file and the operator's AWS credentials, not the cluster, so it works
 * before and after `down`. The whole directory is checked before anything is uploaded; see
 * [com.rustyrazorblade.easydblab.services.documents.ReportZip] for the rules. An upload never
 * replaces an earlier one.
 */
@RequireProfileSetup
@Command(
    name = "upload",
    description = [
        "Upload one directory as a report of the current experiment. It is zipped and stored with the UTC time of the upload; " +
            "no upload replaces an earlier one.",
    ],
    mixinStandardHelpOptions = true,
)
class ReportUpload : PicoBaseCommand() {
    @Parameters(
        arity = "1",
        paramLabel = "DIR",
        description = ["The directory to upload. Every path segment may hold only letters, digits, '.', '_' and '-'."],
    )
    lateinit var dir: File

    /** Anything after the directory: the removed file-list form, refused in [execute] with a message naming the directory form. */
    @Unmatched
    var extra: Array<String> = emptyArray()

    private val reportService: ReportArchiveService by inject()

    override fun execute() {
        require(extra.isEmpty()) {
            "report upload takes one directory, not a list of files: put the files in one directory and run " +
                "'report upload <dir>'. Nothing was uploaded. Extra arguments: ${extra.joinToString(" ")}"
        }
        val uploaded = reportService.upload(clusterState, dir)
        eventBus.emit(
            Event.Report.Uploaded(
                zipKey = uploaded.zip.getKey(),
                unpackedPrefix = uploaded.unpacked.getKey() + "/",
                pointerAdvanced = uploaded.pointerAdvanced,
                files = uploaded.files,
            ),
        )
    }
}
