package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.AwsKeyName
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateKeyPairRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Creates the EC2 key pair that gives the operator SSH access to the cluster's instances, and
 * saves its private key into the profile directory.
 *
 * Lives in `services/aws` so profile setup and `User` need no EC2 types: the caller passes the
 * client its [com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory] built. A permission failure
 * is classified by the same [errorHandler] that reports every other command failure.
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
        val ec2Client =
            if (awsProfile.isNotEmpty()) {
                clientFactory.createEc2ClientWithProfile(awsProfile, region)
            } else {
                clientFactory.createEc2Client(accessKey, secret, region)
            }
        return generate(profileDir, ec2Client)
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
                        Tag
                            .builder()
                            .key("easy_cass_lab")
                            .value("1")
                            .build(),
                    ).build()
            val request =
                CreateKeyPairRequest
                    .builder()
                    .keyName(keyName)
                    .tagSpecifications(tagSpecification)
                    .build()

            val response = ec2Client.createKeyPair(request)

            writeOwnerOnly(File(profileDir, "secret.pem"), response.keyMaterial())

            return keyName
        } catch (e: Ec2Exception) {
            if (errorHandler.describe(e)?.kind == ProviderErrorKind.PERMISSION_DENIED) {
                emitPermissionError(e)
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

    private fun emitPermissionError(exception: Ec2Exception) {
        eventBus.emit(Event.Setup.AwsPermissionError("EC2 CreateKeyPair", exception.message.orEmpty()))
        AWSPolicy.UserIAM.loadAll("ACCOUNT_ID").forEachIndexed { index, policy ->
            eventBus.emit(Event.Setup.AwsPermissionPolicyDisplay(index, policy.name, policy.body))
        }
        eventBus.emit(Event.Setup.AwsPermissionPolicyFooter)
    }
}
