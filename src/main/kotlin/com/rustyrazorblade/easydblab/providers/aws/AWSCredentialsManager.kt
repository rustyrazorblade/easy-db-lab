package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.io.File

/**
 * Manages the AWS credentials file handed to the Packer container during AMI builds.
 *
 * Packer resolves AWS credentials independently of the JVM SDK, so the tool writes a
 * shared-credentials file and mounts it into the container. Credentials are resolved through the
 * same [AwsCredentialsProvider] the rest of the tool uses — static keys or an SSO-backed profile —
 * so AMI builds authenticate identically to every other AWS operation. When the resolved
 * credentials are temporary (e.g. SSO), the session token is written so Packer can authenticate.
 */
class AWSCredentialsManager(
    private val profileDir: File,
    private val credentialsProvider: AwsCredentialsProvider,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * The name of the AWS credentials file
     */
    val credentialsFileName: String = Constants.AWS.DEFAULT_CREDENTIALS_NAME

    /**
     * The AWS credentials file, written from the currently-resolved credentials.
     *
     * The file is always (re)written rather than reused, so temporary credentials (e.g. SSO) are
     * refreshed on each build instead of leaving a stale, expired file on disk.
     */
    val credentialsFile: File by lazy {
        val file = File(profileDir, credentialsFileName)
        val credentials = credentialsProvider.resolveCredentials()
        logger.debug { "Writing AWS credentials file at ${file.absolutePath}" }

        val contents =
            buildString {
                appendLine("[default]")
                appendLine("aws_access_key_id=${credentials.accessKeyId()}")
                appendLine("aws_secret_access_key=${credentials.secretAccessKey()}")
                if (credentials is AwsSessionCredentials) {
                    appendLine("aws_session_token=${credentials.sessionToken()}")
                }
            }
        file.writeText(contents)
        file
    }

    /**
     * Get the absolute path of the credentials file.
     * This is used for volume mounting in Docker containers.
     */
    val credentialsPath: String
        get() = credentialsFile.absolutePath
}
