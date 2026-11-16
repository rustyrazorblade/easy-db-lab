package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The effective Cilium agent configuration, read back from the `cilium-config` ConfigMap.
 *
 * Each field is the ConfigMap value verbatim, or [CiliumConfigSummary.UNSET] when the key is
 * absent, so the report shows what the cluster actually runs rather than what was requested.
 */
data class CiliumConfigSummary(
    val routingMode: String,
    val ipamMode: String,
    val kubeProxyReplacement: String,
    val masqueradeInterfaces: String,
    val nativeRoutingCidr: String,
    val ipv4Masquerade: String,
) {
    companion object {
        const val UNSET = "(unset)"
    }
}

/**
 * One node's ENI and IP allocation state, read from its `CiliumNode` object.
 *
 * @property name The node name.
 * @property eniCount ENIs Cilium manages on the node (the primary and any it attached).
 * @property subnetCidrs The distinct subnet CIDRs those ENIs live in.
 * @property ipsAllocated IPs the operator has allocated to the node's pool.
 * @property ipsUsed IPs from that pool currently assigned to endpoints.
 */
data class CiliumNodeSummary(
    val name: String,
    val eniCount: Int,
    val subnetCidrs: List<String>,
    val ipsAllocated: Int,
    val ipsUsed: Int,
) {
    /** Pool IPs not yet assigned to an endpoint. */
    val ipsAvailable: Int get() = ipsAllocated - ipsUsed
}

/**
 * Everything `platform cni` reports for a Cilium cluster: the agent configuration and the
 * per-node allocation state.
 */
data class CiliumInspection(
    val config: CiliumConfigSummary,
    val nodes: List<CiliumNodeSummary>,
)

/**
 * Reads Cilium's live configuration and per-node ENI state off a running cluster.
 *
 * Runs `kubectl` on the control node over SSH — the CLI and kubeconfig are baked into the AMI,
 * and nothing here needs the developer's machine to reach the cluster API.
 */
interface CiliumInspectionService {
    /**
     * Reads the `cilium-config` ConfigMap and every `CiliumNode` object.
     *
     * @param controlHost The control node, where `kubectl` and the kubeconfig live.
     * @return The parsed inspection, or a failure if either read or parse fails.
     */
    fun inspect(controlHost: Host): Result<CiliumInspection>
}

/**
 * Default [CiliumInspectionService]: two `kubectl ... -o json` reads, parsed with kotlinx.
 *
 * The ConfigMap is the authoritative source for the agent's effective settings. The `CiliumNode`
 * list carries the operator's ENI attachments and IP pool per node, which is the fastest way to
 * see whether ENI IPAM is actually handing out addresses.
 */
class DefaultCiliumInspectionService(
    private val remoteOps: RemoteOperationsService,
) : CiliumInspectionService {
    companion object {
        private const val KUBECTL = "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} kubectl"
        const val CONFIG_COMMAND = "$KUBECTL -n ${Constants.Cilium.NAMESPACE} get cm ${Constants.Cilium.CONFIG_MAP_NAME} -o json"
        const val NODES_COMMAND = "$KUBECTL get ciliumnode -o json"
    }

    override fun inspect(controlHost: Host): Result<CiliumInspection> =
        runCatching {
            val configJson = remoteOps.executeRemotely(controlHost, CONFIG_COMMAND, output = false).text
            val nodesJson = remoteOps.executeRemotely(controlHost, NODES_COMMAND, output = false).text
            CiliumInspection(
                config = parseCiliumConfig(configJson),
                nodes = parseCiliumNodes(nodesJson),
            )
        }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parses `kubectl get cm cilium-config -o json` into a [CiliumConfigSummary].
 *
 * Reads only the `data` map. A missing key renders as [CiliumConfigSummary.UNSET] rather than
 * failing: the ConfigMap's key set varies across Cilium versions and an absent key is itself a
 * useful thing to see in the report.
 */
fun parseCiliumConfig(configMapJson: String): CiliumConfigSummary {
    val data = json.parseToJsonElement(configMapJson).jsonObject.objectOrEmpty("data")

    fun value(key: String): String = data[key]?.jsonPrimitive?.content ?: CiliumConfigSummary.UNSET
    return CiliumConfigSummary(
        routingMode = value("routing-mode"),
        ipamMode = value("ipam"),
        kubeProxyReplacement = value("kube-proxy-replacement"),
        masqueradeInterfaces = value("egress-masquerade-interfaces"),
        nativeRoutingCidr = value("ipv4-native-routing-cidr"),
        ipv4Masquerade = value("enable-ipv4-masquerade"),
    )
}

/**
 * Parses `kubectl get ciliumnode -o json` (a `List`) into one [CiliumNodeSummary] per item.
 *
 * ENIs come from `status.eni.enis` (keyed by ENI id, each carrying its `subnet.cidr`); the pool
 * from `spec.ipam.pool` (keyed by IP); the in-use set from `status.ipam.used` (keyed by IP).
 * A node the operator has not yet populated reports zero for each.
 */
fun parseCiliumNodes(nodeListJson: String): List<CiliumNodeSummary> =
    json
        .parseToJsonElement(nodeListJson)
        .jsonObject
        .arrayOrEmpty("items")
        .map { item -> parseCiliumNode(item.jsonObject) }

private fun parseCiliumNode(node: JsonObject): CiliumNodeSummary {
    val status = node.objectOrEmpty("status")
    val spec = node.objectOrEmpty("spec")
    val enis = status.objectOrEmpty("eni").objectOrEmpty("enis")
    val subnetCidrs =
        enis.values
            .map { eni ->
                eni.jsonObject
                    .objectOrEmpty("subnet")["cidr"]
                    ?.jsonPrimitive
                    ?.content ?: ""
            }.filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    return CiliumNodeSummary(
        name = node.objectOrEmpty("metadata")["name"]?.jsonPrimitive?.content ?: "",
        eniCount = enis.size,
        subnetCidrs = subnetCidrs,
        ipsAllocated = spec.objectOrEmpty("ipam").objectOrEmpty("pool").size,
        ipsUsed = status.objectOrEmpty("ipam").objectOrEmpty("used").size,
    )
}

private fun JsonObject.objectOrEmpty(key: String): JsonObject = (this[key] as? JsonObject) ?: JsonObject(emptyMap())

private fun JsonObject.arrayOrEmpty(key: String): List<JsonElement> = this[key]?.jsonArray?.toList() ?: emptyList()
