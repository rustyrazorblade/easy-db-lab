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
    name = "backup",
    description = ["Back up ClickHouse cluster data to the account S3 bucket"],
)
class ClickHouseBackup : PicoBaseCommand() {
    @Parameters(index = "0", description = ["Name for this backup"])
    lateinit var backupName: String

    @Option(names = ["--async"], description = ["Start backup in background and return immediately"])
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
            .backup(
                controlHost = controlHost,
                backupName = backupName,
                accountBucket = accountBucket,
                clusterName = clusterState.name,
                async = async,
            ).getOrThrow()
    }
}
