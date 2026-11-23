package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.configuration.profile.AxonOps
import com.rustyrazorblade.easydblab.configuration.profile.Profile
import com.rustyrazorblade.easydblab.configuration.profile.ProfileAccess
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.Tailscale
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestinationAccess
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsLocation
import com.rustyrazorblade.easydblab.configuration.profile.aws.LegacyAwsProfileReader
import com.rustyrazorblade.easydblab.configuration.profile.aws.awsProfileSerializers
import java.io.File

/**
 * The profile every test starts from, and the helpers that build one with a field changed.
 *
 * Tests use the real [ProfileStore] over a temporary profile directory rather than a stubbed
 * profile, so a value a service saves is the value the next read sees, as in the tool.
 */
object TestProfiles {
    const val EMAIL = "test@example.com"
    const val REGION = "us-west-2"
    const val KEY_NAME = "test-key"
    const val ACCESS_KEY_ID = "test-access-key"
    const val SECRET_ACCESS_KEY = "test-secret"

    /** An AWS profile with an access key, a key pair, no account bucket and no feature blocks. */
    fun profile(
        email: String = EMAIL,
        region: String = REGION,
        keyName: String = KEY_NAME,
        accountBucket: String = "",
        credentials: AwsCredentials = AwsCredentials.AccessKey(ACCESS_KEY_ID, SECRET_ACCESS_KEY),
        tailscale: Tailscale? = null,
        axonOps: AxonOps? = null,
    ): Profile =
        Profile(
            email = email,
            destination =
                AwsDestination(
                    location = AwsLocation(region),
                    credentials = credentials,
                    keyName = keyName,
                    accountBucket = accountBucket,
                ),
            tailscale = tailscale,
            axonOps = axonOps,
        )

    /** A real store over [profileDir], configured as the tool configures it. */
    fun store(profileDir: File): ProfileStore = ProfileStore(profileDir, awsProfileSerializers, LegacyAwsProfileReader())

    /** A real store over [profileDir] holding [profile]. */
    fun storeWith(
        profileDir: File,
        profile: Profile = profile(),
    ): ProfileStore = store(profileDir).also { it.replace(profile) }

    /** An accessor that always returns [profile], for a test that has no profile directory. */
    fun accessOf(profile: Profile = profile()): ProfileAccess = ProfileAccess { profile }

    /** The AWS destination accessor that always returns [profile]'s destination. */
    fun awsAccessOf(profile: Profile = profile()): AwsDestinationAccess = AwsDestinationAccess.from(accessOf(profile))
}
