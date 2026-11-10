package com.rustyrazorblade.easydblab.configuration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something an execution environment's nodes or account may or may not provide, and which a
 * workload can depend on. A workload that needs an absent capability is skipped and reported
 * rather than deployed to fail its rollout. The set is closed for now; capabilities are added when
 * a second environment needs one.
 *
 * @property absentReason why the workloads needing this capability are skipped without it, stated
 *   as a fact about the environment so any environment lacking it reuses the text.
 */
@Serializable
enum class NodeCapability(
    val absentReason: String,
) {
    @SerialName("systemd-journal")
    SYSTEMD_JOURNAL("this environment's nodes have no systemd journal; pod logs still reach Loki through the collector"),

    @SerialName("cloudwatch")
    CLOUDWATCH("this environment has no CloudWatch"),

    @SerialName("s3")
    S3("this environment has no S3 bucket"),
}

/**
 * The capabilities one environment declares. Consumers ask `capability in capabilities` and never
 * which environment they run in, so a new environment is a new declared set, not a new branch.
 */
@JvmInline
value class NodeCapabilities(
    private val provided: Set<NodeCapability>,
) {
    operator fun contains(capability: NodeCapability): Boolean = capability in provided

    companion object {
        /** AWS nodes run systemd, and the account has CloudWatch and S3. */
        val AWS = NodeCapabilities(NodeCapability.entries.toSet())

        /** Local Docker nodes have no journal, and there is no CloudWatch or S3. */
        val LOCAL_DOCKER = NodeCapabilities(emptySet())

        /**
         * The capabilities of [state]'s environment. Every cluster is AWS until the execution
         * environment is recorded in state (#1040), which will make this return the recorded
         * environment's declared set.
         */
        fun from(state: ClusterState): NodeCapabilities = AWS
    }
}
