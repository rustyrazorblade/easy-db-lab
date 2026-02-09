package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType

/**
 * Configuration for creating EC2 instances.
 *
 * @property serverType The type of server (Cassandra, Stress, Control)
 * @property count Number of instances to create
 * @property instanceType EC2 instance type (e.g., "r3.2xlarge")
 * @property amiId AMI ID to launch instances from
 * @property keyName SSH key pair name
 * @property securityGroupId Security group ID for the instances
 * @property subnetIds List of subnet IDs for round-robin distribution across AZs
 * @property iamInstanceProfile IAM instance profile name for the instances
 * @property ebsConfig Optional EBS volume configuration for additional storage
 * @property tags Tags to apply to instances
 * @property clusterName Name of the cluster for tagging and identification
 * @property startIndex Starting index for instance alias numbering (default 0)
 */
data class InstanceCreationConfig(
    val serverType: ServerType,
    val count: Int,
    val instanceType: String,
    val amiId: String,
    val keyName: String,
    val securityGroupId: SecurityGroupId,
    val subnetIds: List<SubnetId>,
    val iamInstanceProfile: String,
    val ebsConfig: EBSConfig?,
    val tags: Map<String, String>,
    val clusterName: String,
    val startIndex: Int = 0,
)

/**
 * Configuration for EBS volumes attached to instances.
 *
 * @property volumeType EBS volume type (gp2, gp3, io1, io2)
 * @property volumeSize Volume size in GB
 * @property iops Provisioned IOPS (required for gp3, io1, io2)
 * @property throughput Provisioned throughput in MB/s (for gp3 only)
 * @property deviceName Device name for the volume (e.g., /dev/xvdf)
 */
data class EBSConfig(
    val volumeType: String,
    val volumeSize: Int,
    val iops: Int? = null,
    val throughput: Int? = null,
    val deviceName: String = "/dev/xvdf",
)

/**
 * Represents a created EC2 instance with all relevant details.
 *
 * @property instanceId AWS EC2 instance ID (e.g., "i-123abc456def")
 * @property publicIp Public IP address of the instance
 * @property privateIp Private IP address of the instance
 * @property alias Instance alias (e.g., "db0", "stress1")
 * @property availabilityZone AWS availability zone where the instance is running
 * @property serverType The type of server (Cassandra, Stress, Control)
 */
data class CreatedInstance(
    val instanceId: InstanceId,
    val publicIp: String,
    val privateIp: String,
    val alias: String,
    val availabilityZone: String,
    val serverType: ServerType,
)

/**
 * Details about an existing EC2 instance.
 *
 * @property instanceId AWS EC2 instance ID
 * @property state Current instance state (running, stopped, terminated, etc.)
 * @property publicIp Public IP address (may be null if stopped)
 * @property privateIp Private IP address (may be null if terminated)
 * @property availabilityZone AWS availability zone
 * @property instanceType EC2 instance type (e.g., "r3.2xlarge")
 */
data class InstanceDetails(
    val instanceId: InstanceId,
    val state: String,
    val publicIp: String?,
    val privateIp: String?,
    val availabilityZone: String,
    val instanceType: String? = null,
)

/**
 * Represents an existing EC2 instance discovered by tag-based query.
 *
 * Used for discovering instances belonging to a cluster to determine
 * what infrastructure already exists before creating new instances.
 *
 * @property instanceId AWS EC2 instance ID
 * @property publicIp Public IP address (may be null if stopped)
 * @property privateIp Private IP address
 * @property alias Instance alias from Name tag (e.g., "db0")
 * @property availabilityZone AWS availability zone
 * @property serverType Server type from ServerType tag (Cassandra, Stress, Control)
 * @property state Current instance state (running, stopped, etc.)
 */
data class DiscoveredInstance(
    val instanceId: InstanceId,
    val publicIp: String?,
    val privateIp: String?,
    val alias: String,
    val availabilityZone: String,
    val serverType: ServerType,
    val state: String,
) {
    /**
     * Converts this discovered instance to a ClusterHost for use in cluster state.
     */
    fun toClusterHost(): ClusterHost =
        ClusterHost(
            publicIp = publicIp ?: "",
            privateIp = privateIp ?: "",
            alias = alias,
            availabilityZone = availabilityZone,
            instanceId = instanceId,
        )
}
