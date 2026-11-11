package com.rustyrazorblade.easydblab.configuration

typealias Alias = String

/**
 * Represents a host in the cluster.
 *
 * @property public Public IP address of the host
 * @property private Private IP address of the host
 * @property alias Host alias (e.g., "db0", "stress0", "control0")
 * @property availabilityZone AWS availability zone where the host is located
 * @property instanceId EC2 instance ID, needed to reach the host over SSM Session Manager; empty when unknown
 */
data class Host(
    val public: String,
    val private: String,
    val alias: Alias,
    val availabilityZone: String,
    val instanceId: String = "",
)
