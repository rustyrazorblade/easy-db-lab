package com.rustyrazorblade.easycasslab.di

import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.providers.aws.awsModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.inject
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.emr.EmrClient

internal class AWSModuleTest : KoinTest {
    @Test
    fun `should use ProfileCredentialsProvider when awsProfile is set`() {
        // Create a User with awsProfile set
        val testUser =
            User(
                email = "test@example.com",
                region = "us-east-1",
                keyName = "test-key",
                sshKeyPath = "/tmp/test-key.pem",
                awsProfile = "test-profile",
                awsAccessKey = "test-access-key",
                awsSecret = "test-secret-key",
            )

        // Create a test module that provides the User
        val testModule =
            module {
                single { testUser }
            }

        try {
            // Start Koin with awsModule and testModule
            startKoin {
                modules(awsModule, testModule)
            }

            // Get the credentials provider from Koin
            val credentialsProvider: AwsCredentialsProvider by inject()

            // Verify it's a ProfileCredentialsProvider
            assertThat(credentialsProvider).isInstanceOf(ProfileCredentialsProvider::class.java)
        } finally {
            stopKoin()
        }
    }

    @Test
    fun `should use StaticCredentialsProvider when awsProfile is empty`() {
        // Create a User with empty awsProfile
        val testUser =
            User(
                email = "test@example.com",
                region = "us-east-1",
                keyName = "test-key",
                sshKeyPath = "/tmp/test-key.pem",
                awsProfile = "",
                awsAccessKey = "test-access-key",
                awsSecret = "test-secret-key",
            )

        // Create a test module that provides the User
        val testModule =
            module {
                single { testUser }
            }

        try {
            // Start Koin with awsModule and testModule
            startKoin {
                modules(awsModule, testModule)
            }

            // Get the credentials provider from Koin
            val credentialsProvider: AwsCredentialsProvider by inject()

            // Verify it's a StaticCredentialsProvider
            assertThat(credentialsProvider).isInstanceOf(StaticCredentialsProvider::class.java)

            // Verify the credentials are correct
            val credentials = credentialsProvider.resolveCredentials()
            assertThat(credentials.accessKeyId()).isEqualTo("test-access-key")
            assertThat(credentials.secretAccessKey()).isEqualTo("test-secret-key")
        } finally {
            stopKoin()
        }
    }

    @Test
    fun `should provide EmrClient with correct configuration`() {
        // Create a User for testing
        val testUser =
            User(
                email = "test@example.com",
                region = "us-west-2",
                keyName = "test-key",
                sshKeyPath = "/tmp/test-key.pem",
                awsProfile = "",
                awsAccessKey = "test-access-key",
                awsSecret = "test-secret-key",
            )

        // Create a test module that provides the User
        val testModule =
            module {
                single { testUser }
            }

        try {
            // Start Koin with awsModule and testModule
            startKoin {
                modules(awsModule, testModule)
            }

            // Get the EMR client from Koin
            val emrClient: EmrClient by inject()

            // Verify it's not null and is an instance of EmrClient
            assertThat(emrClient).isNotNull
            assertThat(emrClient).isInstanceOf(EmrClient::class.java)
        } finally {
            stopKoin()
        }
    }
}
