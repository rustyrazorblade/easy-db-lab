package com.rustyrazorblade.easycasslab.configuration

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.output.OutputHandler
import com.rustyrazorblade.easycasslab.providers.aws.AWSPolicy
import com.rustyrazorblade.easycasslab.providers.aws.EC2
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.regions.Region
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
typealias SshKeyPath = String

data class Policy(
    val name: String,
    val body: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class User(
    var email: String,
    var region: String,
    var keyName: String,
    var sshKeyPath: String,
    // if true we'll load the profile from the AWS credentials rather than this file
    var awsProfile: String,
    // fallback for people who haven't set up the aws cli
    var awsAccessKey: String,
    var awsSecret: String,
    var axonOpsOrg: String = "",
    var axonOpsKey: String = "",
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
            outputHandler: OutputHandler,
            exception: SdkServiceException,
            operation: String,
        ) {
            with(TermColors()) {
                outputHandler.handleMessage(
                    """
                    |
                    |========================================
                    |AWS PERMISSION ERROR
                    |========================================
                    |
                    |Operation: $operation
                    |Error: ${exception.message}
                    |
                    |To fix this issue, add the following IAM policies to your AWS user.
                    |You need to create THREE separate inline policies:
                    |
                    """.trimMargin(),
                )

                val policies = getRequiredIAMPolicies("ACCOUNT_ID")
                policies.forEachIndexed { index, policy ->
                    outputHandler.handleMessage(
                        """
                        |${green("========================================")}
                        |${green("Policy ${index + 1}: ${policy.name}")}
                        |${green("========================================")}
                        |
                        |${policy.body}
                        |
                        """.trimMargin(),
                    )
                }

                outputHandler.handleMessage(
                    """
                    |========================================
                    |
                    |RECOMMENDED: Create managed policies and attach to a group
                    |  • No size limits (inline policies limited to 5,120 bytes total)
                    |  • Required for EMR/Spark cluster functionality
                    |  • Reusable across multiple users
                    |
                    |To apply these policies:
                    |  1. Go to AWS IAM Console (https://console.aws.amazon.com/iam/)
                    |  2. Create IAM group (e.g., "EasyCassLabUsers")
                    |  3. Create three managed policies:
                    |     - Policies → Create Policy → JSON tab
                    |     - Paste policy content and name: EasyCassLabEC2, EasyCassLabIAM, EasyCassLabEMR
                    |  4. Attach policies to your group
                    |  5. Add your IAM user to the group
                    |
                    |========================================
                    |
                    """.trimMargin(),
                )
            }
        }

        /**
         * Generates an AWS key pair for SSH access to EC2 instances
         *
         * @param context Application context containing profile directory
         * @param awsAccessKey AWS access key for authentication
         * @param awsSecret AWS secret key for authentication
         * @param region AWS region where key pair will be created
         * @param outputHandler Handler for user-facing messages
         * @return Pair of (AwsKeyName, SshKeyPath) containing the AWS key pair name
         *         and the local file path to the private key
         */
        fun generateAwsKeyPair(
            context: Context,
            awsAccessKey: String,
            awsSecret: String,
            region: Region,
            outputHandler: OutputHandler,
        ): Pair<AwsKeyName, SshKeyPath> {
            outputHandler.handleMessage("Generating AWS key pair and SSH credentials...")

            try {
                val ec2 = EC2(awsAccessKey, awsSecret, region)
                val ec2Client = ec2.client

                val keyName = "easy-cass-lab-${UUID.randomUUID()}"
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

                // write the private key into the ~/.easy-cass-lab/profiles/<profile>/ dir
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

                return Pair(keyName, secretFile.absolutePath)
            } catch (e: Ec2Exception) {
                if (e.statusCode() == Constants.HttpStatus.FORBIDDEN || e.awsErrorDetails()?.errorCode() == "UnauthorizedOperation") {
                    handlePermissionError(outputHandler, e, "EC2 CreateKeyPair")
                }
                throw e
            }
        }

        /**
         * Generates a valid S3 bucket name if the user doesn't have one.
         * Generated names follow the pattern: easy-cass-lab-{uuid} (lowercase).
         *
         * NOTE: This method does NOT modify the User object. Callers must update
         * the User object after successfully creating the S3 bucket.
         *
         * @param user The User object to check
         * @return The bucket name (existing or newly generated)
         */
        fun generateBucketName(user: User): String =
            if (user.s3Bucket.isBlank()) {
                "easy-cass-lab-${UUID.randomUUID()}".lowercase()
            } else {
                user.s3Bucket
            }
    }
}
