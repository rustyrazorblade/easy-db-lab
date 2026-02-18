package com.rustyrazorblade.easydblab.providers.aws

/**
 * Represents a security group rule info (inbound or outbound) for display purposes.
 * Different from SecurityGroupRule which is used for creating rules.
 *
 * @property protocol Protocol (e.g., "TCP", "UDP", "All")
 * @property fromPort Start of port range (null for all traffic)
 * @property toPort End of port range (null for all traffic)
 * @property cidrBlocks List of CIDR blocks allowed
 * @property description Optional description of the rule
 */
data class SecurityGroupRuleInfo(
    val protocol: String,
    val fromPort: Int?,
    val toPort: Int?,
    val cidrBlocks: List<String>,
    val description: String?,
)

/**
 * Represents detailed information about a security group for status display
 *
 * @property securityGroupId The security group ID
 * @property name The security group name
 * @property description The security group description
 * @property vpcId The VPC ID the security group belongs to
 * @property inboundRules List of inbound (ingress) rules
 * @property outboundRules List of outbound (egress) rules
 */
data class SecurityGroupDetails(
    val securityGroupId: String,
    val name: String,
    val description: String,
    val vpcId: String,
    val inboundRules: List<SecurityGroupRuleInfo>,
    val outboundRules: List<SecurityGroupRuleInfo>,
)

/**
 * Well-known port numbers for common services
 */
object WellKnownPorts {
    const val SSH = 22
    const val HTTP = 80
    const val HTTPS = 443
    const val MYSQL = 3306
    const val POSTGRESQL = 5432
    const val REDIS = 6379
    const val CASSANDRA_INTERNODE = 7000
    const val CASSANDRA_INTERNODE_SSL = 7001
    const val CASSANDRA_CQL = 9042
    const val CASSANDRA_CQL_SSL = 9142
    const val CASSANDRA_THRIFT = 9160
    const val CASSANDRA_JMX = 10000
}
