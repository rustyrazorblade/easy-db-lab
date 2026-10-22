package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.localstack.LocalDashboardsRefresher
import com.rustyrazorblade.easydblab.localstack.LocalStackCreator
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Writes a local observability stack: a docker compose project in [dir] that reads the shared store
 * with the operator's own AWS profile and writes nothing to it. It works outside a cluster workspace;
 * the account bucket and the profile come from the easy-db-lab profile. See
 * [LocalStackCreator] for what it writes and what it never touches.
 */
@RequireProfileSetup
@Command(
    name = "create",
    description = [
        "Write a local Grafana, Mimir, Loki and Tempo stack that reads the shared store, into DIR. " +
            "Start it with 'docker compose up -d' in DIR. Re-run it to pick up new tenants and dashboards.",
    ],
    mixinStandardHelpOptions = true,
)
class LocalCreate : PicoBaseCommand() {
    @Parameters(
        arity = "1",
        paramLabel = "DIR",
        description = ["The stack directory: created when missing, used when empty, rewritten when it holds a stack this command wrote."],
    )
    lateinit var dir: File

    @Option(
        names = ["--tenant"],
        description = ["The home tenant: the tenant of the stable mimir, loki and tempo datasources."],
    )
    var tenant: String = Constants.Observability.DEFAULT_TENANT

    private val creator: LocalStackCreator by inject()

    override fun execute() = creator.create(dir, tenant)
}

/**
 * Refreshes the dashboards of a running local stack in [dir] with no restart: the packaged ones, or
 * those under [from]. It never calls docker, so no container is recreated. See
 * [LocalDashboardsRefresher].
 */
@Command(
    name = "dashboards",
    description = [
        "Replace the dashboards of the running local stack in DIR, with no restart: the packaged dashboards, " +
            "or the <folder>/<file>.json tree given with --from.",
    ],
    mixinStandardHelpOptions = true,
)
class LocalDashboards : PicoBaseCommand() {
    @Parameters(arity = "1", paramLabel = "DIR", description = ["The stack directory 'observability local create' wrote."])
    lateinit var dir: File

    @Option(
        names = ["--from"],
        paramLabel = "DIR",
        description = ["A directory of <folder>/<file>.json dashboards to use instead of the packaged ones; no rebuild needed."],
    )
    var from: File? = null

    private val refresher: LocalDashboardsRefresher by inject()

    override fun execute() = refresher.refresh(dir, from)
}
