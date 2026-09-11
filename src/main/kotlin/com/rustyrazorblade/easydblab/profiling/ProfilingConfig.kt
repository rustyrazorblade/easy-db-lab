package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import kotlinx.serialization.Serializable

/**
 * The desired profiling state for one node — written by the CLI to
 * [Constants.Profiling.DESIRED_STATE_PATH] and read by that node's reconciler on every pass.
 *
 * [asprofArgs] is a list, not a single string, and that is load-bearing. The list preserves the
 * user's exact tokenization all the way to the node, where the reconciler reads it NUL-delimited
 * via `yq -0` into a bash array and expands it directly as argv. A single string would force the
 * node to re-split it, and re-splitting is where quoting bugs live — so the injection surface here
 * is structurally absent rather than escaped correctly.
 *
 * @property enabled Stopping is an explicit `false`, never a deleted file, so "profiling is off"
 *   and "nobody has configured this node" stay distinguishable.
 * @property loopInterval JFR rotation interval handed to `asprof --loop`.
 * @property retentionMinutes Age bound on the profile directory; applies to unshipped chunks too.
 * @property maxBytes Byte ceiling on the profile directory, pruned oldest-first.
 * @property pyroscopeUrl Ingest base URL; the reconciler POSTs to `$pyroscopeUrl/ingest`.
 * @property clusterName Shipped as a Pyroscope series label alongside the node's hostname.
 * @property updatedAt When the CLI last wrote this document, for operator forensics.
 */
@Serializable
data class ProfilingConfig(
    val enabled: Boolean,
    val asprofArgs: List<String>,
    val loopInterval: String = Constants.Profiling.DEFAULT_LOOP_INTERVAL,
    val retentionMinutes: Int = Constants.Profiling.DEFAULT_RETENTION_MINUTES,
    val maxBytes: Long = Constants.Profiling.DEFAULT_MAX_BYTES,
    val pyroscopeUrl: String = "",
    val clusterName: String = "",
    val updatedAt: String = "",
)

/** Renders this desired state as the JSON document the node's reconciler reads. */
fun ProfilingConfig.toJson(): String = profilingJson.encodeToString(ProfilingConfig.serializer(), this)

/**
 * Parses a desired-state document.
 *
 * @param source what was being read, for the diagnostic logged when it cannot be parsed.
 * @return the parsed config, or null if the document is empty, truncated, or malformed.
 */
fun parseProfilingConfig(
    document: String,
    source: String = "",
): ProfilingConfig? = decodeProfilingDocumentOrNull(document, ProfilingConfig.serializer(), source)

/**
 * Builds the Pyroscope ingest base URL for a node's reconciler.
 *
 * Local mode addresses the control node on its private IP deliberately: cluster services are
 * always reached on the private (Tailscale) address, never the public one. A redirect cluster
 * ships profiles to the external stack instead, so [TelemetryRedirect.profiles] wins.
 *
 * @param controlNodeIp private IP of the control node (used only in local mode).
 * @param telemetryRedirect when non-null, the external Pyroscope ingest URL is returned instead.
 */
fun pyroscopeIngestBaseUrl(
    controlNodeIp: String,
    telemetryRedirect: TelemetryRedirect? = null,
): String = telemetryRedirect?.profiles ?: "http://$controlNodeIp:${Constants.K8s.PYROSCOPE_PORT}"
