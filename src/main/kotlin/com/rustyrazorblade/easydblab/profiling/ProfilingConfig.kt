package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServiceDns
import com.rustyrazorblade.easydblab.configuration.ServicePublishing
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
 * @property retentionMinutes Age bound on shipped chunks. Unshipped and rejected chunks are never
 *   pruned.
 * @property maxBytes Byte ceiling on the profile directory. Shipped chunks are pruned oldest-first;
 *   when only unshipped or rejected chunks remain at the ceiling, the node stops recording instead.
 * @property pyroscopeUrl Ingest base URL; the reconciler POSTs to `$pyroscopeUrl/ingest`.
 * @property clusterName Shipped as a Pyroscope series label alongside the node's hostname.
 * @property tenant The cluster's observability tenant, sent in `X-Scope-OrgID` on every upload. A
 *   document written before tenants existed reads as the default tenant.
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
    val tenant: String = Constants.Observability.DEFAULT_TENANT,
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
 * The Pyroscope ingest base URL for an in-cluster profile producer (a pod's Pyroscope agent).
 *
 * Local mode is the Pyroscope Service ([ServiceDns.pyroscope]): every in-cluster producer
 * resolves cluster DNS, so no node IP is involved. A redirect cluster ships profiles to the
 * external stack instead, so [TelemetryRedirect.profiles] wins.
 *
 * @param telemetryRedirect when non-null, the external Pyroscope ingest URL is returned instead.
 * @param serviceDns the cluster namespace's Service addresses.
 */
fun pyroscopeIngestBaseUrl(
    telemetryRedirect: TelemetryRedirect?,
    serviceDns: ServiceDns,
): String = telemetryRedirect?.profiles ?: serviceDns.pyroscope()

/**
 * The Pyroscope URL a host process on a cluster node sends profiles to: the node-local relay in the
 * host-network Alloy eBPF agent, on loopback. A host process (the db-node profiling reconciler)
 * cannot resolve cluster DNS; the relay forwards to the cluster's Pyroscope or, under telemetry
 * redirect, to the external stack, so the URL is the same in both modes.
 */
fun nodeLocalProfilesRelay(): String = "http://127.0.0.1:${Constants.K8s.ALLOY_RELAY_PORT}"

/**
 * Pyroscope as a caller outside the cluster reaches it on a node easy-db-lab owns: the node's
 * private address at the Pyroscope NodePort. Only for callers that cannot resolve cluster DNS and
 * do not run on a cluster node: EMR's Spark JVMs and a browser following the dashboard link.
 *
 * @param nodeIp private address of a cluster node (any node serves a NodePort; the control node by convention).
 */
fun pyroscopeNodePortUrl(nodeIp: String): String = "http://$nodeIp:${Constants.K8s.PYROSCOPE_NODE_PORT}"

/**
 * The Pyroscope UI address a browser opens from the dashboards' "Pyroscope UI" link.
 *
 * Under [ServicePublishing.NodePorts] it is the control node's private IP at the Pyroscope NodePort:
 * a browser going through the SOCKS tunnel resolves names on the control node, which does not know
 * `.svc` names. Otherwise it is the Pyroscope Service ([ServiceDns.pyroscope]).
 *
 * @throws IllegalStateException under [ServicePublishing.NodePorts] when [controlHost] is null
 */
fun pyroscopeBrowserUrl(
    publishing: ServicePublishing,
    controlHost: ClusterHost?,
    serviceDns: ServiceDns,
): String =
    when (publishing) {
        ServicePublishing.NodePorts ->
            pyroscopeNodePortUrl(
                checkNotNull(controlHost) {
                    "The dashboards' Pyroscope UI link needs the control node's private IP, " +
                        "but the cluster state has no control node."
                }.privateIp,
            )
        ServicePublishing.ClusterIp -> serviceDns.pyroscope()
    }
