package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import software.amazon.awssdk.profiles.ProfileFile

/**
 * Whether an AWS profile signs in through IAM Identity Center (SSO). A refused store check names
 * `aws sso login --profile <p>` only for such a profile: for a profile with static keys the login
 * command does nothing, and the keys themselves were refused.
 */
fun interface AwsLoginKind {
    /** True when [profile] signs in through SSO. */
    fun usesSso(profile: String): Boolean
}

/**
 * [AwsLoginKind] from the AWS config and credentials files, as the SDK reads them (`AWS_CONFIG_FILE`
 * and `AWS_SHARED_CREDENTIALS_FILE` honoured): a profile is an SSO profile when its own section has
 * an `sso_session` or an `sso_start_url`. A profile in neither file is not.
 *
 * @property profiles reads the files, on each call, so an edit made since start is seen.
 */
class ProfileFileLoginKind(
    private val profiles: () -> ProfileFile = ProfileFile::defaultProfileFile,
) : AwsLoginKind {
    override fun usesSso(profile: String): Boolean =
        profiles()
            .profile(profile)
            .map { found -> Constants.AWS.SSO_PROFILE_PROPERTIES.any { found.property(it).isPresent } }
            .orElse(false)
}
