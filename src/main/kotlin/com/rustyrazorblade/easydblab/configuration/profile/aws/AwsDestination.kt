package com.rustyrazorblade.easydblab.configuration.profile.aws

import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.Destination
import com.rustyrazorblade.easydblab.configuration.profile.Profile
import com.rustyrazorblade.easydblab.configuration.profile.ProfileAccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The AWS destination: clusters run on EC2 in one region of the account the credentials reach.
 *
 * It holds the account resources setup creates once per profile, the EC2 key pair and the account
 * bucket. Both start blank on a new profile; the first operation that needs one creates it and saves
 * it through the profile store, so they are read at each use through [AwsDestinationAccess].
 *
 * @property keyName the EC2 key pair whose private key is the profile's `secret.pem`; blank until created
 * @property accountBucket the profile's account bucket for images, builds and observability data; blank until created
 * @property sshTransport how this machine reaches port 22 on the nodes
 */
@Serializable
@SerialName(AwsDestination.TYPE)
data class AwsDestination(
    val location: AwsLocation,
    val credentials: AwsCredentials,
    val keyName: String = "",
    val accountBucket: String = "",
    val sshTransport: SshTransport = SshTransport.Direct,
) : Destination {
    override val kind: String
        get() = TYPE

    override val displayName: String
        get() = DISPLAY_NAME

    companion object {
        /** The `type` of an AWS destination in the profile file. */
        const val TYPE = "aws"

        /** How setup and `profile show` name the destination. */
        const val DISPLAY_NAME = "AWS"
    }
}

/**
 * Where in AWS a profile's clusters run.
 *
 * A separate block so that each destination type names its own location (a region, a project and
 * zone, a kubeconfig context) under the same `location` key.
 */
@Serializable
data class AwsLocation(
    val region: String,
)

/**
 * How the tool authenticates to AWS: a named AWS CLI profile, or an access key pair.
 *
 * Sealed because both forms are AWS and live together; the `type` key picks the form.
 */
@Serializable
sealed interface AwsCredentials {
    /** The name of an AWS CLI profile in the operator's own AWS configuration (SSO or static keys). */
    @Serializable
    @SerialName(AwsProfile.TYPE)
    data class AwsProfile(
        val name: String,
    ) : AwsCredentials {
        companion object {
            /** The credential `type` in the profile file. */
            const val TYPE = "aws-profile"
        }
    }

    /** A static access key pair, for operators who have not set up the AWS CLI. */
    @Serializable
    @SerialName(AccessKey.TYPE)
    data class AccessKey(
        val accessKeyId: String,
        val secretAccessKey: String,
    ) : AwsCredentials {
        companion object {
            /** The credential `type` in the profile file. */
            const val TYPE = "access-key"
        }
    }
}

/**
 * Registers the AWS destination for the `type` key of the profile file.
 *
 * The profile store takes the serializers module from its caller, so the neutral store names no AWS
 * type. Each later destination type adds its own registration to the module it hands the store.
 */
val awsProfileSerializers: SerializersModule =
    SerializersModule {
        polymorphic(Destination::class) {
            subclass(AwsDestination::class)
        }
    }

/**
 * Reads the active profile's AWS destination at the moment a value is needed.
 *
 * AWS code asks this rather than holding an [AwsDestination], so a key pair or bucket saved after a
 * service was built is still the one the service sees.
 */
fun interface AwsDestinationAccess {
    /** The AWS destination as it is saved now. */
    fun current(): AwsDestination

    companion object {
        /**
         * The AWS destination of [profiles]' current profile; fails naming the destination's type
         * when the profile is for somewhere else.
         */
        fun from(profiles: ProfileAccess): AwsDestinationAccess = AwsDestinationAccess { profiles.current().aws }
    }
}

/**
 * This profile's AWS destination; fails naming the destination's type when the profile is for
 * somewhere else.
 */
val Profile.aws: AwsDestination
    get() =
        when (val destination = destination) {
            is AwsDestination -> destination
            else ->
                error(
                    "This command needs an AWS profile, but the active profile's destination is " +
                        "'${destination.kind}'. Use a profile whose destination is AWS.",
                )
        }

/** This profile with [change] applied to its AWS destination; for a store `update`. */
fun Profile.withAws(change: (AwsDestination) -> AwsDestination): Profile = copy(destination = change(aws))
