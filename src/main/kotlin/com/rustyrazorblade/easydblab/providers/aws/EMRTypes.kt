package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants

/**
 * Configuration for creating an EMR cluster.
 *
 * @property clusterName Name of the EMR cluster
 * @property releaseLabel EMR release version (e.g., "emr-7.9.0")
 * @property logUri S3 URI for EMR logs (e.g., "s3://bucket/emr-logs/")
 * @property subnetId Subnet ID where the cluster will be launched
 * @property ec2KeyName SSH key pair name for cluster instances
 * @property masterInstanceType Instance type for the master node
 * @property coreInstanceType Instance type for core nodes
 * @property coreInstanceCount Number of core nodes
 * @property serviceRole IAM service role for EMR
 * @property jobFlowRole IAM role for EC2 instances in the cluster
 * @property applications List of applications to install (e.g., ["Spark"])
 * @property additionalSecurityGroups Additional security groups to attach to EMR instances
 *                                    (needed for EMR to access other EC2 instances like Cassandra)
 * @property tags Tags to apply to the cluster
 * @property configurations EMR configurations/classifications (e.g., spark-defaults, spark-env)
 */
data class EMRClusterConfig(
    val clusterName: String,
    val releaseLabel: String = "emr-7.9.0",
    val logUri: String,
    val subnetId: SubnetId,
    val ec2KeyName: String,
    val masterInstanceType: String,
    val coreInstanceType: String,
    val coreInstanceCount: Int,
    val serviceRole: String = Constants.AWS.Roles.EMR_SERVICE_ROLE,
    val jobFlowRole: String = Constants.AWS.Roles.EMR_EC2_ROLE,
    val applications: List<String> = listOf("Spark"),
    val additionalSecurityGroups: List<String> = emptyList(),
    val tags: Map<String, String>,
    val bootstrapActions: List<BootstrapAction> = emptyList(),
    val configurations: List<EMRConfiguration> = emptyList(),
)

/**
 * A bootstrap action to run on EMR cluster nodes during startup.
 *
 * @property name Display name for the bootstrap action
 * @property scriptS3Path S3 URI of the shell script to execute (e.g., "s3://bucket/path/script.sh")
 * @property args Optional arguments to pass to the script
 */
data class BootstrapAction(
    val name: String,
    val scriptS3Path: String,
    val args: List<String> = emptyList(),
)

/**
 * An EMR classification configuration (e.g., spark-defaults, spark-env).
 *
 * @property classification The classification name (e.g., "spark-defaults")
 * @property properties Key-value configuration properties
 */
data class EMRConfiguration(
    val classification: String,
    val properties: Map<String, String>,
)

/**
 * Result of EMR cluster creation.
 *
 * @property clusterId The unique EMR cluster ID (e.g., "j-ABC123XYZ")
 * @property clusterName The cluster name
 * @property masterPublicDns The public DNS name of the master node
 * @property state The current state of the cluster
 */
data class EMRClusterResult(
    val clusterId: ClusterId,
    val clusterName: String,
    val masterPublicDns: String?,
    val state: String,
)

/**
 * Status of an EMR cluster.
 *
 * @property clusterId The unique EMR cluster ID
 * @property state The current state of the cluster (STARTING, BOOTSTRAPPING, RUNNING, WAITING, TERMINATING, TERMINATED, TERMINATED_WITH_ERRORS)
 * @property stateChangeReason Reason for the state change, if any
 */
data class EMRClusterStatus(
    val clusterId: ClusterId,
    val state: String,
    val stateChangeReason: String?,
)

/**
 * EMR cluster states that indicate the cluster is ready for use.
 */
object EMRClusterStates {
    const val STARTING = "STARTING"
    const val BOOTSTRAPPING = "BOOTSTRAPPING"
    const val RUNNING = "RUNNING"
    const val WAITING = "WAITING"
    const val TERMINATING = "TERMINATING"
    const val TERMINATED = "TERMINATED"
    const val TERMINATED_WITH_ERRORS = "TERMINATED_WITH_ERRORS"

    /**
     * States that indicate the cluster is ready to accept jobs.
     */
    val READY_STATES = setOf(RUNNING, WAITING)

    /**
     * States that indicate the cluster is no longer usable.
     */
    val TERMINAL_STATES = setOf(TERMINATED, TERMINATED_WITH_ERRORS)

    /**
     * States that indicate the cluster is starting up.
     */
    val STARTING_STATES = setOf(STARTING, BOOTSTRAPPING)
}
