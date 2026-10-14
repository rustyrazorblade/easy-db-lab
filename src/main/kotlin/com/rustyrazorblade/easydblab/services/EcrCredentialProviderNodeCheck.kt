package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService

/**
 * Checks that nodes were launched from an AMI carrying the kubelet's ECR credential provider: the
 * `ecr-credential-provider` binary and its `CredentialProviderConfig`, which the base image bakes
 * where K3s looks for them.
 *
 * Pods pull ECR images through that provider and no pull secret. A node from an older AMI joins
 * K3s normally and only fails later, when a pod on it cannot pull an ECR image. Checking the files
 * before K3s starts turns that into an immediate failure that names the node.
 */
class EcrCredentialProviderNodeCheck(
    remoteOps: RemoteOperationsService,
) {
    private val files = NodeFileCheck(remoteOps)

    /**
     * The nodes among [hosts] that lack any of [REQUIRED_FILES], with what each lacks. A node
     * that cannot be reached fails the check rather than passing it.
     */
    fun nodesMissingProvider(hosts: List<ClusterHost>): List<NodeMissingFiles> = files.nodesMissing(hosts, REQUIRED_FILES)

    companion object {
        /** The files `install_ecr_credential_provider.sh` bakes into the base AMI. */
        val REQUIRED_FILES: List<String> = Constants.K3s.ECR_CREDENTIAL_PROVIDER_FILES
    }
}
