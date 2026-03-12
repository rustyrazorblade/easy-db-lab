package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.User
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Manages AWS credentials for Docker container operations (e.g., Packer builds).
 *
 * Supports two credential modes:
 * - **Profile mode**: when `user.awsProfile` is set, the host `~/.aws/` directory is
 *   mounted into the container so named profiles (including SSO) work as expected.
 * - **Key mode**: when `user.awsProfile` is empty, a credentials file is created from
 *   `user.awsAccessKey` / `user.awsSecret` and mounted into the container.
 */
class AWSCredentialsManager(
    private val profileDir: File,
    private val user: User,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * True when credentials are supplied via an AWS named profile rather than static keys.
     */
    val isProfileBased: Boolean
        get() = user.awsProfile.isNotEmpty()

    /**
     * The AWS profile name to use. Only meaningful when [isProfileBased] is true.
     */
    val profileName: String
        get() = user.awsProfile

    /**
     * Path to the host `~/.aws/` directory. Only meaningful when [isProfileBased] is true.
     */
    val hostAwsConfigDir: String
        get() = "${System.getProperty("user.home")}/.aws"

    /**
     * The name of the AWS credentials file (key-mode only).
     */
    val credentialsFileName: String = Constants.AWS.DEFAULT_CREDENTIALS_NAME

    /**
     * Get or create the AWS credentials file (key-mode only).
     * If the file doesn't exist it will be created with the user's access key and secret.
     */
    val credentialsFile: File by lazy {
        val file = File(profileDir, credentialsFileName)
        if (!file.exists()) {
            logger.debug { "Creating AWS credentials file at ${file.absolutePath}" }
            file.writeText(
                """[default]
                |aws_access_key_id=${user.awsAccessKey}
                |aws_secret_access_key=${user.awsSecret}
            """.trimMargin("|"),
            )
        }
        file
    }

    /**
     * Absolute path of the credentials file (key-mode only).
     * Used for volume mounting in Docker containers.
     */
    val credentialsPath: String
        get() = credentialsFile.absolutePath
}
