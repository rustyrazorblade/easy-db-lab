package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.configuration.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AWSCredentialsManagerTest {
    @TempDir
    lateinit var profileDir: File

    private fun userWithKeys() =
        User(
            email = "test@example.com",
            region = "us-east-1",
            keyName = "test-key",
            awsProfile = "",
            awsAccessKey = "AKIAIOSFODNN7EXAMPLE",
            awsSecret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        )

    private fun userWithProfile(profile: String = "my-profile") =
        User(
            email = "test@example.com",
            region = "us-east-1",
            keyName = "test-key",
            awsProfile = profile,
            awsAccessKey = "",
            awsSecret = "",
        )

    @Nested
    inner class KeyMode {
        private lateinit var manager: AWSCredentialsManager

        @BeforeEach
        fun setup() {
            manager = AWSCredentialsManager(profileDir, userWithKeys())
        }

        @Test
        fun `isProfileBased returns false when awsProfile is empty`() {
            assertThat(manager.isProfileBased).isFalse()
        }

        @Test
        fun `creates credentials file with access key and secret`() {
            val content = manager.credentialsFile.readText()
            assertThat(content).contains("aws_access_key_id=AKIAIOSFODNN7EXAMPLE")
            assertThat(content).contains("aws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            assertThat(content).contains("[default]")
        }

        @Test
        fun `credentialsPath points to file in profileDir`() {
            assertThat(manager.credentialsPath).startsWith(profileDir.absolutePath)
        }

        @Test
        fun `does not overwrite existing credentials file`() {
            val existingContent = "existing content"
            File(profileDir, manager.credentialsFileName).writeText(existingContent)

            assertThat(manager.credentialsFile.readText()).isEqualTo(existingContent)
        }
    }

    @Nested
    inner class ProfileMode {
        private lateinit var manager: AWSCredentialsManager

        @BeforeEach
        fun setup() {
            manager = AWSCredentialsManager(profileDir, userWithProfile("staging"))
        }

        @Test
        fun `isProfileBased returns true when awsProfile is set`() {
            assertThat(manager.isProfileBased).isTrue()
        }

        @Test
        fun `profileName returns the configured profile`() {
            assertThat(manager.profileName).isEqualTo("staging")
        }

        @Test
        fun `hostAwsConfigDir points to home directory aws folder`() {
            val expectedDir = "${System.getProperty("user.home")}/.aws"
            assertThat(manager.hostAwsConfigDir).isEqualTo(expectedDir)
        }
    }
}
