package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The layout of a cluster's observability data under `observability/` in the account bucket.
 *
 * This is the one place that builds those paths. Mimir, Loki, Tempo and Pyroscope run native
 * multi-tenancy and lay out their own tenant directories, so their backends get a fixed prefix with
 * no tenant in it. The annotation backups have no backend to do that, so they carry the tenant in the
 * path: `observability/annotations/<tenant>/<backup>.json`.
 *
 * @property bucket The account-level S3 bucket.
 * @property tenant The cluster's observability tenant.
 */
data class ObservabilityStore(
    val bucket: String,
    val tenant: String,
) {
    companion object {
        /**
         * The store of the given cluster: its account bucket and its tenant.
         *
         * @throws IllegalStateException if the cluster has no account bucket yet.
         */
        fun from(clusterState: ClusterState): ObservabilityStore {
            val bucket =
                clusterState.s3Bucket
                    ?: error("S3 bucket not configured for cluster '${clusterState.name}'. Run 'easy-db-lab up' first.")
            return ObservabilityStore(bucket, clusterState.tenant())
        }
    }

    private fun root(): ClusterS3Path = ClusterS3Path.root(bucket).resolve(Constants.Observability.PREFIX)

    /** Key prefix of Tempo's live backend; Tempo makes the tenant directories under it. */
    fun tracesPrefix(): String = root().resolve(Constants.Observability.TRACES_DIR).getKey()

    /** Mimir's storage prefix; Mimir makes the tenant directories under it. */
    fun metricsPrefix(): String = Constants.Observability.METRICS_ROOT

    /** Key prefix of Loki's backend; Loki lays out its tenants' chunks and its index tables under it. */
    fun logsPrefix(): String = root().resolve(Constants.Observability.LOGS_DIR).getKey()

    /** Key prefix of the Pyroscope server's live backend; Pyroscope makes its own layout under it. */
    fun profilesPrefix(): String = root().resolve(Constants.Observability.PROFILES_DIR).getKey()

    /** The tenant's Grafana annotation backups. */
    fun annotationsRoot(): ClusterS3Path = root().resolve(Constants.Observability.ANNOTATIONS_DIR).resolve(tenant)

    /** File of one Grafana annotations backup. */
    fun annotationsArtifact(name: SnapshotName): ClusterS3Path = annotationsRoot().resolve("$name.json")
}

/**
 * The name of one annotations backup in the observability store: `<yyyyMMdd-HHmmss>_<name>-<clusterId>`.
 *
 * Names sort by time across every cluster in a tenant, and carry the cluster so that two clusters
 * saving in the same second never collide. Only S3 keys use this form: `_` and the length make it an
 * invalid Kubernetes object name.
 *
 * @property timestamp UTC time of the snapshot, `yyyyMMdd-HHmmss`.
 * @property cluster The cluster label, `<name>-<clusterId>`.
 */
data class SnapshotName(
    val timestamp: String,
    val cluster: String,
) {
    override fun toString(): String = "${timestamp}${SEPARATOR}$cluster"

    companion object {
        private const val SEPARATOR = "_"
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Formats [at] the way snapshot names carry it. */
        fun timestampOf(at: Instant): String = TIMESTAMP_FORMAT.format(at.atOffset(ZoneOffset.UTC))

        /** The name of a snapshot [clusterState] takes at [at]. */
        fun of(
            clusterState: ClusterState,
            at: Instant,
        ): SnapshotName = SnapshotName(timestampOf(at), clusterState.clusterLabelName())

        /**
         * The name of a snapshot [clusterState] takes at [at], moved to the first later second whose
         * name [taken] reports free.
         *
         * Names have one-second resolution, so two backups of one cluster in the same second (a
         * `grafana backup` and then the pre-teardown backup in `down`) would otherwise write the same
         * key and the second would overwrite the first.
         *
         * @param taken whether a snapshot already exists under a name, e.g. its S3 key or prefix.
         */
        fun firstFree(
            clusterState: ClusterState,
            at: Instant,
            taken: (SnapshotName) -> Boolean,
        ): SnapshotName =
            generateSequence(at) { it.plusSeconds(1) }
                .map { of(clusterState, it) }
                .first { !taken(it) }
    }
}
