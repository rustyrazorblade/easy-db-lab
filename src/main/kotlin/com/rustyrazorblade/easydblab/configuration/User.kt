package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.rustyrazorblade.easydblab.Constants

typealias AwsKeyName = String

data class Policy(
    val name: String,
    val body: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class User(
    var email: String,
    var region: String,
    var keyName: String,
    // if true we'll load the profile from the AWS credentials rather than this file
    var awsProfile: String,
    // fallback for people who haven't set up the aws cli
    var awsAccessKey: String,
    var awsSecret: String,
    var axonOpsOrg: String = "",
    var axonOpsKey: String = "",
    // Tailscale OAuth credentials for VPN access
    var tailscaleClientId: String = "",
    var tailscaleClientSecret: String = "",
    var tailscaleTag: String = Constants.Tailscale.DEFAULT_DEVICE_TAG,
    // Profile-level S3 bucket for shared resources (AMIs, base images, etc.)
    var s3Bucket: String = "",
    // How SSH reaches cluster nodes from this machine: directly, or tunneled over SSM Session Manager
    var sshTransport: SshTransport = SshTransport.Direct,
) {
    /**
     * Returns true when Tailscale credentials are configured in this profile.
     * Used by [Init] to determine whether to enable Tailscale routing for the cluster.
     */
    fun isTailscaleEnabled(): Boolean = tailscaleClientId.isNotBlank() && tailscaleClientSecret.isNotBlank()

    /**
     * Returns true when both AxonOps credentials are configured in this profile.
     * Both are required: [Up] and [cassandra.Start] skip AxonOps unless the org and the key are set.
     */
    fun isAxonOpsEnabled(): Boolean = axonOpsOrg.isNotBlank() && axonOpsKey.isNotBlank()
}
