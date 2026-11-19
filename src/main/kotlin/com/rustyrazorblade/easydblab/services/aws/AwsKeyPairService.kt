package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.AwsKeyName
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.ConfigurationException
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import com.rustyrazorblade.easydblab.services.toEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateKeyPairRequest
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * Creates the EC2 key pair that gives the operator SSH access to the cluster's instances, and
 * saves its private key into the profile directory.
 *
 * Lives in `services/aws` so profile setup and `User` need no EC2 types: profile setup passes the
 * region and credentials it collected, and this service builds the EC2 client for them with its
 * [AWSClientFactory] and closes it. A permission failure is classified by the same [errorHandler]
 * that reports every other command failure.
 */
class AwsKeyPairService(
    private val eventBus: EventBus,
    private val clientFactory: AWSClientFactory,
    private val errorHandler: ProviderErrorHandler,
) {
    private companion object {
        const val OWNER_ONLY = "rw-------"
    }

    /**
     * Creates a key pair with the credentials the user just entered: the named AWS [awsProfile]
     * when it is not empty, otherwise the static [accessKey] and [secret].
     *
     * @return the key pair's name
     * @throws CommandFailedException when EC2 denies the call; the missing policies were already shown
     */
    fun generate(
        profileDir: File,
        region: String,
        awsProfile: String,
        accessKey: String,
        secret: String,
    ): AwsKeyName {
        // This method built the client, so it closes it; the overload below never closes a client it was given.
        return ec2Client(region, awsProfile, accessKey, secret).use { generate(profileDir, it) }
    }

    /**
     * Gives the profile's existing key pair every marker tag it lacks, with the [user]'s
     * credentials. A key pair created before the `easy_db_lab` key existed carries only the legacy
     * one. Does nothing when the profile has no key pair yet.
     */
    fun ensureMarkerTags(user: User) {
        if (user.keyName.isBlank()) return
        ec2Client(user.region, user.awsProfile, user.awsAccessKey, user.awsSecret).use { ensureMarkerTags(user.keyName, it) }
    }

    /**
     * Adds every marker tag ([Constants.Vpc.MARKER_TAGS]) the key pair [keyName] lacks; its other
     * tags stay. Nothing is written when it carries them all.
     *
     * Only a key pair that carries the legacy marker is tagged: the IAM policy allows `ec2:*` on a
     * resource only through that tag, and a key pair without it was not marked by this tool.
     * A key pair that does not exist fails with a message naming it.
     */
    fun ensureMarkerTags(
        keyName: AwsKeyName,
        ec2Client: Ec2Client,
    ) {
        val keyPair =
            try {
                ec2Client
                    .describeKeyPairs(DescribeKeyPairsRequest.builder().keyNames(keyName).build())
                    .keyPairs()
                    .single()
            } catch (e: Ec2Exception) {
                if (e.awsErrorDetails()?.errorCode() != Constants.AWS.EC2_KEY_PAIR_NOT_FOUND) throw e
                throw ConfigurationException(
                    "The key pair $keyName named in the profile does not exist in this region. " +
                        "Clear keyName in the profile settings, then run `profile setup` to create a new one.",
                    e,
                )
            }
        val current = keyPair.tags().associate { it.key() to it.value() }
        if (current[Constants.Vpc.LEGACY_TAG_KEY] != Constants.Vpc.TAG_VALUE) {
            log.info { "Key pair $keyName has no ${Constants.Vpc.LEGACY_TAG_KEY} marker; leaving its tags alone" }
            return
        }
        val missing = Constants.Vpc.MARKER_TAGS.filter { (key, value) -> current[key] != value }
        if (missing.isEmpty()) return
        ec2Client.createTags(
            CreateTagsRequest
                .builder()
                .resources(keyPair.keyPairId())
                .tags(
                    missing.map { (key, value) ->
                        Tag
                            .builder()
                            .key(key)
                            .value(value)
                            .build()
                    },
                ).build(),
        )
        log.info { "Added the missing marker tags to key pair $keyName" }
    }

    /** An EC2 client for the named AWS [awsProfile] when it is not empty, otherwise the static [accessKey] and [secret]. */
    private fun ec2Client(
        region: String,
        awsProfile: String,
        accessKey: String,
        secret: String,
    ): Ec2Client =
        if (awsProfile.isNotEmpty()) {
            clientFactory.createEc2ClientWithProfile(awsProfile, region)
        } else {
            clientFactory.createEc2Client(accessKey, secret, region)
        }

    /**
     * Creates a key pair tagged for the lab and writes the private key to `secret.pem` in
     * [profileDir], readable only by its owner.
     *
     * @return the key pair's name
     * @throws CommandFailedException when EC2 denies the call; the missing policies were already shown
     */
    fun generate(
        profileDir: File,
        ec2Client: Ec2Client,
    ): AwsKeyName {
        eventBus.emit(Event.Setup.GeneratingKeyPairAndSsh)

        try {
            val keyName = "easy-db-lab-${UUID.randomUUID()}"
            val tagSpecification =
                TagSpecification
                    .builder()
                    .resourceType(ResourceType.KEY_PAIR)
                    .tags(
                        Constants.Vpc.MARKER_TAGS.map { (key, value) ->
                            Tag
                                .builder()
                                .key(key)
                                .value(value)
                                .build()
                        },
                    ).build()
            val request =
                CreateKeyPairRequest
                    .builder()
                    .keyName(keyName)
                    .tagSpecifications(tagSpecification)
                    .build()

            val response = ec2Client.createKeyPair(request)

            writeOwnerOnly(File(profileDir, Constants.ConfigPaths.PROFILE_SSH_KEY_FILE), response.keyMaterial())

            return keyName
        } catch (e: Ec2Exception) {
            val description = errorHandler.describe(e)
            if (description?.kind == ProviderErrorKind.PERMISSION_DENIED) {
                emitPermissionError(description)
                log.error(e) { "EC2 denied CreateKeyPair" }
                throw CommandFailedException("EC2 denied CreateKeyPair: ${e.message}", e)
            }
            throw e
        }
    }

    /**
     * Writes [content] to a new file that is owner-only from the moment it exists, so the private key
     * is never readable by others, even briefly. An existing file is replaced.
     */
    private fun writeOwnerOnly(
        file: File,
        content: String,
    ) {
        val path = file.toPath()
        Files.deleteIfExists(path)
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)))
        Files.writeString(path, content)
    }

    /**
     * Reports the denial as the provider error the command failure reporter would emit, with its
     * remedy naming the EC2 policy, then lists every lab policy for a user setting up a profile.
     */
    private fun emitPermissionError(description: ErrorDescription) {
        eventBus.emit(description.toEvent())
        val policies = AWSPolicy.UserIAM.loadAll(Constants.AWS.ACCOUNT_ID_PLACEHOLDER)
        policies.forEachIndexed { index, policy ->
            eventBus.emit(Event.Setup.AwsPermissionPolicyDisplay(index, policy.name, policy.body))
        }
        eventBus.emit(Event.Setup.AwsPermissionPolicyFooter(policies.map { it.name }))
    }
}
