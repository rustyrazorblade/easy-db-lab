package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateKeyPairRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

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
) {
    companion object {
        val log = KotlinLogging.logger {}

        /**
         * Loads the required IAM policies from resources with account ID substitution.
         * Returns a list of Policy objects with names and content for all three required policies.
         *
         * @param accountId The AWS account ID to substitute for ACCOUNT_ID placeholder
         */
        internal fun getRequiredIAMPolicies(accountId: String): List<Policy> = AWSPolicy.UserIAM.loadAll(accountId)

        /**
         * Displays helpful error message when AWS permission is denied
         */
        private fun handlePermissionError(
            eventBus: EventBus,
            exception: SdkServiceException,
            operation: String,
        ) {
            eventBus.emit(
                Event.Setup.AwsPermissionError(operation, exception.message ?: ""),
            )

            val policies = getRequiredIAMPolicies("ACCOUNT_ID")
            policies.forEachIndexed { index, policy ->
                eventBus.emit(
                    Event.Setup.AwsPermissionPolicyDisplay(index, policy.name, policy.body),
                )
            }

            eventBus.emit(Event.Setup.AwsPermissionPolicyFooter)
        }

        /**
         * Generates an AWS key pair for SSH access to EC2 instances.
         * The private key is automatically saved to ${profileDir}/secret.pem
         *
         * @param context Application context containing profile directory
         * @param ec2Client EC2 client for AWS API calls (supports both profile and credential auth)
         * @param outputHandler Handler for user-facing messages
         * @return The AWS key pair name
         */
        fun generateAwsKeyPair(
            context: Context,
            ec2Client: Ec2Client,
            eventBus: EventBus,
        ): AwsKeyName {
            eventBus.emit(Event.Setup.GeneratingKeyPairAndSsh("Generating AWS key pair and SSH credentials..."))

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

                // write the private key into the ~/.easy-db-lab/profiles/<profile>/ dir
                val secretFile = File(context.profileDir, "secret.pem")
                secretFile.writeText(response.keyMaterial())

                // set permissions
                val perms =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    )

                log.info { "Setting secret file permissions $perms" }
                Files.setPosixFilePermissions(secretFile.toPath(), perms)

                return keyName
            } catch (e: Ec2Exception) {
                if (e.statusCode() == Constants.HttpStatus.FORBIDDEN || e.awsErrorDetails()?.errorCode() == "UnauthorizedOperation") {
                    handlePermissionError(eventBus, e, "EC2 CreateKeyPair")
                }
                throw e
            }
        }
    }
}
