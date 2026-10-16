package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants

/**
 * The local stack's Tempo configuration: the cluster's `tempo.yaml` parsed and patched as typed data
 * ([YamlDocument]). Tempo 3.0.3 has no flag for the S3 `insecure` and `forcepathstyle` keys or the
 * `blocklist_poll_*` keys, so unlike Mimir and Loki it cannot be adapted with flags alone.
 *
 * Tempo runs as its single binary, which always starts its backend scheduler and worker. The patches
 * make them inert: compaction stays disabled (already in `tempo.yaml`, so no compaction or retention
 * job is ever scheduled), the scheduler's work file is never written, and usage reporting is off.
 * The only write left is the tenant index, once per tenant per poll, which the store gate refuses.
 * Blocks are found by listing the bucket on Tempo's default 5-minute poll, so the newest blocks of
 * a cluster just torn down appear without a fresh tenant index; that poll is kept on purpose,
 * because every poll lists every block of every tenant.
 */
object TempoLocalConfig {
    /** Never: no periodic flush of `tempo/work.json`. */
    private const val SCHEDULER_FLUSH_INTERVAL = "87600h"

    /** A path that cannot be created, so a shutdown flush of `work.json` stops before the backend write. */
    private const val UNWRITABLE_WORK_PATH = "/dev/null/backend-scheduler"

    /**
     * Search and metrics older than this read the backend blocks. Tempo rejects anything at or under
     * its 30s query-end cutoff; at its 15m default, the last 15 minutes would go only to this
     * Tempo's own empty live store.
     */
    private const val QUERY_BACKEND_AFTER = "1m"

    /**
     * Keys for Tempo to sign with. The signer replaces the signature with the operator's own, so
     * these never reach S3.
     */
    private const val PLACEHOLDER_KEY = "local-stack"

    /**
     * The live stack's config: [clusterYaml] reading the store through the gate at [s3Endpoint]
     * (`host:port`, plain HTTP, path-style).
     */
    fun live(
        clusterYaml: String,
        s3Endpoint: String,
    ): String =
        YamlDocument.patch(
            clusterYaml,
            listOf(
                YamlPatch.set("storage.trace.s3.endpoint", s3Endpoint),
                YamlPatch.set("storage.trace.s3.insecure", "true"),
                YamlPatch.set("storage.trace.s3.forcepathstyle", "true"),
                YamlPatch.set("storage.trace.s3.access_key", PLACEHOLDER_KEY),
                YamlPatch.set("storage.trace.s3.secret_key", PLACEHOLDER_KEY),
            ) + READ_ONLY,
        )

    /**
     * The offline stack's config: [clusterYaml] reading the exported tenant directories under
     * [tracesPath] with the local backend, which the single binary lists itself. Its tenant-index and
     * work-file writes fail on the read-only mount, logged and harmless (spike S5).
     */
    fun offline(
        clusterYaml: String,
        tracesPath: String,
    ): String =
        YamlDocument.patch(
            clusterYaml,
            listOf(
                YamlPatch.set("storage.trace.backend", "local"),
                YamlPatch.remove("storage.trace.s3"),
                YamlPatch.set("storage.trace.local.path", tracesPath),
            ) + READ_ONLY,
        )

    /**
     * How long a cold read may take: the server's read and write timeouts and the querier's search
     * and trace-by-id timeouts, all [Constants.LocalStack.COLD_READ_TIMEOUT_SECONDS], the wait of the
     * local Grafana in front of this Tempo. Range reads of large blocks through the gate outlasted
     * Tempo 3.0.3's defaults (30s, and 10s by id).
     */
    private val COLD_READ = "${Constants.LocalStack.COLD_READ_TIMEOUT_SECONDS}s"

    /**
     * What makes the single binary a reader: the inert scheduler and worker, search over the backend,
     * and the time a cold read may take.
     */
    private val READ_ONLY =
        listOf(
            YamlPatch.set("server.http_server_read_timeout", COLD_READ),
            YamlPatch.set("server.http_server_write_timeout", COLD_READ),
            YamlPatch.set("querier.search.query_timeout", COLD_READ),
            YamlPatch.set("querier.trace_by_id.query_timeout", COLD_READ),
            YamlPatch.set("storage.trace.blocklist_poll_fallback", "false"),
            YamlPatch.remove("storage.trace.blocklist_poll"),
            YamlPatch.remove("storage.trace.blocklist_poll_tenant_index_builders"),
            YamlPatch.set("overrides.defaults.compaction.compaction_disabled", "true"),
            YamlPatch.set("backend_scheduler.backend_flush_interval", SCHEDULER_FLUSH_INTERVAL),
            YamlPatch.set("backend_scheduler.local_work_path", UNWRITABLE_WORK_PATH),
            YamlPatch.set("usage_report.reporting_enabled", "false"),
            YamlPatch.set("query_frontend.search.query_backend_after", QUERY_BACKEND_AFTER),
            YamlPatch.set("query_frontend.metrics.query_backend_after", QUERY_BACKEND_AFTER),
        )
}
