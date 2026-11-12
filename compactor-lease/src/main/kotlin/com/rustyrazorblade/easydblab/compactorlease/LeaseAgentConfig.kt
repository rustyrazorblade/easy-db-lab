package com.rustyrazorblade.easydblab.compactorlease

import java.net.URI
import java.nio.file.Path

/**
 * The lease agent's configuration, read from the environment the `account-compactor` pod sets.
 *
 * @property bucket the account bucket holding the lease.
 * @property region the account bucket's region; the agent signs for it.
 * @property holder this pod's identity: the cluster label, `/`, and the pod name (Downward API).
 * @property leaseDir the directory shared with the gates.
 * @property scratchDirs the compactors' scratch directories, wiped on acquire.
 * @property timing the lease's timing.
 * @property endpoint an S3 endpoint to use instead of the region's, with path-style addressing, or
 *   `null` for AWS. Only a test against LocalStack sets it.
 */
data class LeaseAgentConfig(
    val bucket: String,
    val region: String,
    val holder: String,
    val leaseDir: Path,
    val scratchDirs: List<Path>,
    val timing: LeaseTiming,
    val endpoint: URI? = null,
) {
    companion object {
        const val BUCKET_ENV = "S3_BUCKET"
        const val REGION_ENV = "AWS_REGION"
        const val CLUSTER_LABEL_ENV = "CLUSTER_LABEL"
        const val POD_NAME_ENV = "POD_NAME"
        const val LEASE_DIR_ENV = "LEASE_DIR"

        /** The scratch directories, separated by `:`, as `PATH` is. */
        const val SCRATCH_DIRS_ENV = "SCRATCH_DIRS"

        /** An optional S3 endpoint; see [endpoint]. */
        const val ENDPOINT_ENV = "S3_ENDPOINT"

        /**
         * The configuration in [env].
         *
         * @throws IllegalArgumentException naming a variable that is missing or blank, or a timing
         *   that breaks the lease's margin.
         */
        fun fromEnvironment(env: Map<String, String>): LeaseAgentConfig {
            fun value(name: String): String = requireNotNull(env[name]?.takeIf { it.isNotBlank() }) { "$name must be set" }
            return LeaseAgentConfig(
                bucket = value(BUCKET_ENV),
                region = value(REGION_ENV),
                holder = "${value(CLUSTER_LABEL_ENV)}/${value(POD_NAME_ENV)}",
                leaseDir = Path.of(value(LEASE_DIR_ENV)),
                scratchDirs = value(SCRATCH_DIRS_ENV).split(':').filter { it.isNotBlank() }.map { Path.of(it) },
                timing = LeaseTiming.fromEnvironment(env),
                endpoint = env[ENDPOINT_ENV]?.takeIf { it.isNotBlank() }?.let { URI(it) },
            )
        }
    }
}
