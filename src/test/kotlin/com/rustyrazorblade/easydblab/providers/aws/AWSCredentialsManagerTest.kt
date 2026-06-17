package com.rustyrazorblade.easydblab.providers.aws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.io.File

/**
 * Verifies that the credentials file written for the Packer container reflects the credentials
 * resolved from the AWS provider, including a session token when the credentials are temporary
 * (the SSO case) and omitting it for long-lived static keys.
 */
class AWSCredentialsManagerTest {
    @Test
    fun `writes session token when resolved credentials are temporary`(
        @TempDir tempDir: File,
    ) {
        val provider =
            StaticCredentialsProvider.create(
                AwsSessionCredentials.create("AKIASSO", "sso-secret", "session-token-123"),
            )
        val manager = AWSCredentialsManager(tempDir, provider)

        val contents = manager.credentialsFile.readText()

        assertThat(contents)
            .contains("[default]")
            .contains("aws_access_key_id=AKIASSO")
            .contains("aws_secret_access_key=sso-secret")
            .contains("aws_session_token=session-token-123")
    }

    @Test
    fun `omits session token for static credentials`(
        @TempDir tempDir: File,
    ) {
        val provider =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("AKIASTATIC", "static-secret"),
            )
        val manager = AWSCredentialsManager(tempDir, provider)

        val contents = manager.credentialsFile.readText()

        assertThat(contents)
            .contains("aws_access_key_id=AKIASTATIC")
            .contains("aws_secret_access_key=static-secret")
        assertThat(contents).doesNotContain("aws_session_token")
    }
}
