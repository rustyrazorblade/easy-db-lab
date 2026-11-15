package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something an execution environment's nodes, account or admission policy may or may not provide,
 * and which a workload can depend on. A workload that needs an absent capability is skipped and
 * reported rather than deployed to fail its rollout. The set is closed for now; capabilities are
 * added when a second environment needs one.
 *
 * Each entry's serial name is its name everywhere outside the code: in `state.json` (the Jackson
 * name, kept equal to the kotlinx one), in `init --capability`, and in the `cluster-config`
 * `capabilities` key.
 *
 * @property serialName the capability's name outside the code
 * @property absentReason why the workloads needing this capability are skipped without it, stated
 *   as a fact about the environment so any environment lacking it reuses the text.
 */
@Serializable
enum class NodeCapability(
    val serialName: String,
    val absentReason: String,
) {
    @SerialName("systemd-journal")
    @JsonProperty("systemd-journal")
    SYSTEMD_JOURNAL("systemd-journal", "this environment's nodes have no systemd journal"),

    @SerialName("cloudwatch")
    @JsonProperty("cloudwatch")
    CLOUDWATCH("cloudwatch", "this environment has no CloudWatch"),

    @SerialName("s3")
    @JsonProperty("s3")
    S3("s3", "this environment has no S3 bucket"),

    @SerialName("privileged")
    @JsonProperty("privileged")
    PRIVILEGED("privileged", "this cluster's namespace does not admit privileged pods"),

    @SerialName("host-network")
    @JsonProperty("host-network")
    HOST_NETWORK("host-network", "this cluster's namespace does not admit pods on the host network"),

    @SerialName("host-path")
    @JsonProperty("host-path")
    HOST_PATH("host-path", "this cluster's namespace does not admit hostPath volumes"),

    @SerialName("node-ssh")
    @JsonProperty("node-ssh")
    NODE_SSH("node-ssh", "easy-db-lab cannot run commands on this environment's nodes over SSH"),
    ;

    companion object {
        /** The capability named [name], or null when no capability has that serial name. */
        fun fromSerialName(name: String): NodeCapability? = entries.firstOrNull { it.serialName == name }
    }
}

/**
 * The capabilities one environment provides. Consumers ask `capability in capabilities` and never
 * which environment they run in, so a new environment is a new declared set, not a new branch.
 */
@JvmInline
value class NodeCapabilities(
    private val provided: Set<NodeCapability>,
) {
    operator fun contains(capability: NodeCapability): Boolean = capability in provided

    /** The present capabilities, in enum order, so anything built from them is stable. */
    fun present(): List<NodeCapability> = NodeCapability.entries.filter { it in provided }

    /** The absent capabilities, in enum order. */
    fun absent(): List<NodeCapability> = NodeCapability.entries.filterNot { it in provided }

    companion object {
        /** AWS nodes run systemd and sshd, admit any pod, and the account has CloudWatch and S3. */
        val AWS = NodeCapabilities(NodeCapability.entries.toSet())

        /**
         * Local Docker nodes admit privileged, host-network and hostPath pods, but have no journal,
         * no SSH, and there is no CloudWatch or S3.
         */
        val LOCAL_DOCKER =
            NodeCapabilities(setOf(NodeCapability.PRIVILEGED, NodeCapability.HOST_NETWORK, NodeCapability.HOST_PATH))

        /**
         * The capabilities [state] records. A state written before capabilities were recorded has
         * none and reads as [AWS], the only environment such a state can describe.
         */
        fun from(state: ClusterState): NodeCapabilities = state.capabilities?.let(::NodeCapabilities) ?: AWS
    }
}
