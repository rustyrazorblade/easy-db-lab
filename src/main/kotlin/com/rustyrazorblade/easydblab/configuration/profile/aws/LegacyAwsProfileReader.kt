package com.rustyrazorblade.easydblab.configuration.profile.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.AxonOps
import com.rustyrazorblade.easydblab.configuration.profile.LegacyProfileReader
import com.rustyrazorblade.easydblab.configuration.profile.Profile
import com.rustyrazorblade.easydblab.configuration.profile.Tailscale

/**
 * Converts the flat `settings.yaml` of earlier releases, which only ever described AWS, into a
 * [Profile] with an [AwsDestination].
 *
 * It is a pure function over the file's scalar values. The getter keys that Jackson wrote
 * (`isTailscaleEnabled`, `isAxonOpsEnabled`) and `sshKeyPath` are ignored: whether a feature is on is
 * always derived from its values. It also maps the current file's dotted paths back to these flat
 * keys, so profile setup prefills from one shape for either file.
 */
class LegacyAwsProfileReader : LegacyProfileReader {
    override fun holdsCredentials(values: Map<String, String>): Boolean = credentials(values) != null

    override fun migrate(values: Map<String, String>): Profile? {
        val credentials = credentials(values) ?: return null
        val value = { key: String -> values[key].orEmpty() }
        return Profile(
            email = value(EMAIL),
            destination =
                AwsDestination(
                    location = AwsLocation(value(REGION)),
                    credentials = credentials,
                    keyName = value(KEY_NAME),
                    accountBucket = value(S3_BUCKET),
                    sshTransport = values[SSH_TRANSPORT]?.let { SshTransport.fromProfile(it) } ?: SshTransport.Direct,
                ),
            tailscale =
                block(value(TAILSCALE_CLIENT_ID), value(TAILSCALE_CLIENT_SECRET)) { id, secret ->
                    Tailscale(id, secret, values[TAILSCALE_TAG] ?: Constants.Tailscale.DEFAULT_DEVICE_TAG)
                },
            axonOps = block(value(AXONOPS_ORG), value(AXONOPS_KEY)) { org, key -> AxonOps(org, key) },
        )
    }

    override fun setupValues(paths: Map<String, String>): Map<String, String> {
        val credentialType = paths["destination.credentials.type"]
        val credentials =
            when (credentialType) {
                AwsCredentials.AwsProfile.TYPE -> listOf(AWS_PROFILE to "destination.credentials.name")
                AwsCredentials.AccessKey.TYPE ->
                    listOf(
                        AWS_ACCESS_KEY to "destination.credentials.accessKeyId",
                        AWS_SECRET to "destination.credentials.secretAccessKey",
                    )
                else -> emptyList()
            }
        return (CURRENT_PATHS + credentials)
            .mapNotNull { (key, path) -> paths[path]?.let { key to it } }
            .toMap()
    }

    /** The credentials the values name: an AWS CLI profile name wins over an access key pair. */
    private fun credentials(values: Map<String, String>): AwsCredentials? {
        val profileName = values[AWS_PROFILE].orEmpty()
        val keyId = values[AWS_ACCESS_KEY].orEmpty()
        val secret = values[AWS_SECRET].orEmpty()
        return when {
            profileName.isNotBlank() -> AwsCredentials.AwsProfile(profileName)
            keyId.isNotBlank() && secret.isNotBlank() -> AwsCredentials.AccessKey(keyId, secret)
            else -> null
        }
    }

    /** A block when either value is set, keeping a blank partner so setup can prefill it; else none. */
    private fun <T> block(
        first: String,
        second: String,
        build: (String, String) -> T,
    ): T? = if (first.isNotBlank() || second.isNotBlank()) build(first, second) else null

    companion object {
        const val EMAIL = "email"
        const val REGION = "region"
        const val KEY_NAME = "keyName"
        const val AWS_PROFILE = "awsProfile"
        const val AWS_ACCESS_KEY = "awsAccessKey"
        const val AWS_SECRET = "awsSecret"
        const val S3_BUCKET = "s3Bucket"
        const val SSH_TRANSPORT = "sshTransport"
        const val AXONOPS_ORG = "axonOpsOrg"
        const val AXONOPS_KEY = "axonOpsKey"
        const val TAILSCALE_CLIENT_ID = "tailscaleClientId"
        const val TAILSCALE_CLIENT_SECRET = "tailscaleClientSecret"
        const val TAILSCALE_TAG = "tailscaleTag"

        /** Each flat setup key and the dotted path that holds it in the current file. */
        private val CURRENT_PATHS =
            listOf(
                EMAIL to "email",
                REGION to "destination.location.region",
                KEY_NAME to "destination.keyName",
                S3_BUCKET to "destination.accountBucket",
                SSH_TRANSPORT to "destination.sshTransport",
                TAILSCALE_CLIENT_ID to "tailscale.clientId",
                TAILSCALE_CLIENT_SECRET to "tailscale.clientSecret",
                TAILSCALE_TAG to "tailscale.tag",
                AXONOPS_ORG to "axonOps.org",
                AXONOPS_KEY to "axonOps.key",
            )
    }
}
