package com.rustyrazorblade.easydblab.compactorlease

import com.rustyrazorblade.easydblab.compactorlease.aws.withS3LeaseStore
import io.github.oshai.kotlinlogging.KotlinLogging

/** The logback configuration the agent jar logs with; the CLI never loads it. */
private const val LOGBACK_CONFIG = "compactor-lease-logback.xml"

/**
 * Runs the lease agent in the `lease` container of the `account-compactor` pod until the pod stops.
 *
 * It reads [LeaseAgentConfig] from the environment, signs for the account bucket's region with the
 * default credentials chain (the node's instance role through IMDS, see [withS3LeaseStore]), and runs [LeaseLoop] with the
 * [GateFile] and the [ScratchWipe]. Every lease transition is logged to stdout.
 */
fun main() {
    if (System.getProperty("logback.configurationFile") == null) {
        System.setProperty("logback.configurationFile", LOGBACK_CONFIG)
    }
    val log = KotlinLogging.logger("com.rustyrazorblade.easydblab.compactorlease.LeaseAgent")
    val config = LeaseAgentConfig.fromEnvironment(System.getenv())
    log.info {
        "Lease agent ${config.holder} starting on s3://${config.bucket}/${LeaseRecord.KEY} in ${config.region}; " +
            "TTL ${config.timing.ttl}, worst-case stop ${config.timing.worstCaseStop}"
    }
    withS3LeaseStore(config) { objects ->
        val gate = GateFile(config.leaseDir)
        // A restarted container must not run on a grant, or report a readiness, from before it stopped.
        gate.reset()
        val loop =
            LeaseLoop(
                objects = objects,
                holder = config.holder,
                timing = config.timing,
                clock = SystemLeaseClock,
                gate = gate,
                wipeScratch = ScratchWipe(config.scratchDirs)::run,
            )
        // An interrupt closes the gate and ends the loop; the client is closed and main returns.
        loop.runUntilInterrupted { Thread.sleep(it.inWholeMilliseconds) }
    }
    log.info { "Lease agent ${config.holder} stopped" }
}
