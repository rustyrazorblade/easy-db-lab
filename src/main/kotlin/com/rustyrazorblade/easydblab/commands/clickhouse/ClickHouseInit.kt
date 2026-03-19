package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClickHouseConfig
import com.rustyrazorblade.easydblab.events.Event
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

    @Option(
        names = ["--replicas-per-shard"],
        description = ["Number of replicas per shard (default: ${Constants.ClickHouse.DEFAULT_REPLICAS_PER_SHARD})"],
    )
    var replicasPerShard: Int = Constants.ClickHouse.DEFAULT_REPLICAS_PER_SHARD

    @Option(
        names = ["--s3-tier-move-factor"],
        description = ["Move data to S3 tier when local disk free space falls below this fraction (0.0-1.0) (default: \${DEFAULT-VALUE})"],
    )
    var s3TierMoveFactor: Double = Constants.ClickHouse.DEFAULT_S3_TIER_MOVE_FACTOR

    override fun execute() {
        // Validate s3TierMoveFactor range [0.0, 1.0] per ClickHouse requirements
        require(s3TierMoveFactor in 0.0..1.0) {
            "s3TierMoveFactor must be in range [0.0, 1.0], got: $s3TierMoveFactor"
        }

        val state = clusterStateManager.load()
        val config =
            ClickHouseConfig(
                s3CacheSize = s3CacheSize,
                s3CacheOnWrite = s3CacheOnWrite,
                replicasPerShard = replicasPerShard,
                s3TierMoveFactor = s3TierMoveFactor,
            )
        state.updateClickHouseConfig(config)
        clusterStateManager.save(state)

        eventBus.emit(
            Event.ClickHouse.ConfigSaved(
                replicasPerShard = replicasPerShard,
                s3CacheSize = s3CacheSize,
                s3CacheOnWrite = s3CacheOnWrite,
                s3TierMoveFactor = s3TierMoveFactor,
            ),
        )
    }
}
