package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants
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
 * Builds the Pyroscope ingest base URL from the control node's private IP.
 *
 * Private IP deliberately: cluster services are always addressed on the private (Tailscale) address,
 * never the public one.
 */
fun pyroscopeIngestBaseUrl(controlNodeIp: String): String = "http://$controlNodeIp:${Constants.K8s.PYROSCOPE_PORT}"
