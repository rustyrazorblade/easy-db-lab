package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.export.ExportSelection
import com.rustyrazorblade.easydblab.export.ExportSelector
import com.rustyrazorblade.easydblab.export.ExportSelectorChoice
import com.rustyrazorblade.easydblab.export.ExportTarget
import com.rustyrazorblade.easydblab.export.TenantExporter
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import org.koin.core.component.inject
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * The selector options of `observability export`. `--tenant` alone is a tenant export, and beside
 * `--experiment` it names the experiment's tenant, so the options cannot be one picocli exclusive
 * group (picocli would refuse `--experiment e --tenant t`, and its message would not name all three
 * choices). [ExportSelection.of] makes them exclusive, with a message that names all three.
 */
class ExportSelectorOptions {
    @Option(
        names = ["--tenant"],
        paramLabel = "TENANT",
        description = ["Export a copy of this tenant's store; with --experiment, the experiment's tenant."],
    )
    var tenant: String? = null

    @Option(
        names = ["--cluster"],
        paramLabel = "NAME-ID",
        description = ["Export only this cluster's data (repeatable)."],
    )
    var clusters: List<String> = emptyList()

    @Option(
        names = ["--experiment"],
        paramLabel = "NAME",
        description = ["Export every cluster of this experiment, in --tenant, the workspace's tenant, or default."],
    )
    var experiment: String? = null

    /** The options as given. */
    fun choice(): ExportSelectorChoice = ExportSelectorChoice(tenant, clusters, experiment)
}

/**
 * Writes an export: a zip of observability data that opens offline as the local stack, with plain
 * `docker compose up` and no network once the images are pulled. It reads the account bucket with
 * the operator's profile and writes only the local zip; nothing in S3 changes. It works outside a
 * cluster workspace. See [TenantExporter] for the tenant export.
 */
@RequireProfileSetup
@Command(
    name = "export",
    description = [
        "Export observability data to a new zip that opens offline with 'docker compose up'. Choose exactly one of " +
            "--tenant, --cluster or --experiment. Profiles are not included (issue #1016).",
    ],
    mixinStandardHelpOptions = true,
)
class ObservabilityExport : PicoBaseCommand() {
    @Parameters(
        arity = "1",
        paramLabel = "PATH",
        description = ["The zip to write: a new .zip file in an existing directory. It is never overwritten."],
    )
    lateinit var path: File

    @ArgGroup(exclusive = false, heading = "What to export (exactly one kind):%n")
    var selector: ExportSelectorOptions = ExportSelectorOptions()

    private val tenantExporter: TenantExporter by inject()

    override fun execute() {
        val workspaceTenant = if (clusterStateManager.exists()) clusterState.tenant() else null
        val selection = ExportSelection.of(selector.choice(), workspaceTenant)
        val target = ExportTarget.of(path)
        when (val chosen = selection.selector) {
            is ExportSelector.Tenant -> tenantExporter.export(target, chosen.tenant)
            is ExportSelector.Experiment -> {
                selection.tenantChoice?.let {
                    eventBus.emit(Event.Export.TenantChosen(chosen.key.name, it.tenant, it.source.name.lowercase()))
                }
                throw CommandFailedException("Exporting an experiment is not available yet; export its tenant with --tenant")
            }
            is ExportSelector.Clusters ->
                throw CommandFailedException("Exporting clusters is not available yet; export their tenant with --tenant")
        }
    }
}
