package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClickHouseConfig
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Configure ClickHouse settings before starting the cluster.
 *
 * This command stores ClickHouse configuration in the cluster state,
 * which is read by `clickhouse start` when deploying the cluster.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "init",
    description = ["Configure ClickHouse settings (run before clickhouse start)"],
)
class ClickHouseInit : PicoBaseCommand() {
    @Option(
        names = ["--s3-cache"],
        description = ["Size of the local S3 cache (default: ${Constants.ClickHouse.DEFAULT_S3_CACHE_SIZE})"],
    )
    var s3CacheSize: String = Constants.ClickHouse.DEFAULT_S3_CACHE_SIZE

    @Option(
        names = ["--s3-cache-on-write"],
        description = ["Cache data during write operations (default: true)"],
    )
    var s3CacheOnWrite: String = Constants.ClickHouse.DEFAULT_S3_CACHE_ON_WRITE

    override fun execute() {
        val state = clusterStateManager.load()
        val config = ClickHouseConfig(s3CacheSize = s3CacheSize, s3CacheOnWrite = s3CacheOnWrite)
        state.updateClickHouseConfig(config)
        clusterStateManager.save(state)

        outputHandler.handleMessage("ClickHouse configuration saved.")
        outputHandler.handleMessage("  S3 cache size: $s3CacheSize")
        outputHandler.handleMessage("  S3 cache on write: $s3CacheOnWrite")
    }
}
