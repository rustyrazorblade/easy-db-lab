package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.getControlHost
import com.rustyrazorblade.easydblab.services.ClickHouseBackupService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@McpCommand
@RequireProfileSetup
@Command(
    name = "restore",
    description = ["Restore ClickHouse cluster data from a named backup in the account S3 bucket"],
)
class ClickHouseRestore : PicoBaseCommand() {
    @Parameters(index = "0", description = ["Name of the backup to restore from"])
    lateinit var backupName: String

    @Option(names = ["--async"], description = ["Start restore in background and return immediately"])
    var async: Boolean = false

    private val clickHouseBackupService: ClickHouseBackupService by inject()

    override fun execute() {
        val controlHost =
            clusterState.getControlHost()
                ?: error("No control nodes found. Please ensure the environment is running.")
        val accountBucket =
            requireNotNull(clusterState.s3Bucket) {
                "Account bucket not configured. Run 'easy-db-lab up' first."
            }
        clickHouseBackupService
            .restore(
                controlHost = controlHost,
                backupName = backupName,
                accountBucket = accountBucket,
                async = async,
            ).getOrThrow()
    }
}
