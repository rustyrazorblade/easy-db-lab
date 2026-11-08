package com.rustyrazorblade.easydblab.compactorlease

import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

/** The logback configuration the agent jar logs with; the CLI never loads it. */
private const val LOGBACK_CONFIG = "compactor-lease-logback.xml"

/**
 * Runs the lease agent in the `lease` container of the `account-compactor` pod until the pod stops.
 *
 * It reads [LeaseAgentConfig] from the environment, signs for the account bucket's region with the
 * default credentials chain (the node's instance role through IMDS), and runs [LeaseLoop] with the
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
    S3Client
        .builder()
        .region(Region.of(config.region))
        .credentialsProvider(DefaultCredentialsProvider.builder().build())
        .apply { config.endpoint?.let { endpointOverride(it).forcePathStyle(true) } }
        .build()
        .use { s3 ->
            val gate = GateFile(config.leaseDir)
            // A restarted container must not run on a grant written before it stopped.
            gate.revoke()
            val loop =
                LeaseLoop(
                    objects = S3ConditionalObjects(s3, config.bucket),
                    holder = config.holder,
                    timing = config.timing,
                    clock = SystemLeaseClock,
                    gate = gate,
                    wipeScratch = ScratchWipe(config.scratchDirs)::run,
                )
            while (true) {
                Thread.sleep(loop.step().inWholeMilliseconds)
            }
        }
}
