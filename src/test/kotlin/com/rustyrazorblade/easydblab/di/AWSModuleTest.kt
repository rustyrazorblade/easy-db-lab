package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.aws.awsModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.inject
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.emr.EmrClient

internal class AWSModuleTest {
    @Nested
    inner class WithProfileCredentials : BaseKoinTest() {
        override fun coreTestModules(): List<Module> = emptyList()

        override fun additionalTestModules(): List<Module> =
            listOf(
                awsModule,
                module {
                    single {
                        User(
                            email = "test@example.com",
                            region = "us-east-1",
                            keyName = "test-key",
                            awsProfile = "test-profile",
                            awsAccessKey = "test-access-key",
                            awsSecret = "test-secret-key",
                        )
                    }
                },
            )

        @Test
        fun `should use ProfileCredentialsProvider when awsProfile is set`() {
            val credentialsProvider: AwsCredentialsProvider by inject()

            assertThat(credentialsProvider).isInstanceOf(ProfileCredentialsProvider::class.java)
        }
    }

    @Nested
    inner class WithStaticCredentials : BaseKoinTest() {
        override fun coreTestModules(): List<Module> = emptyList()

        override fun additionalTestModules(): List<Module> =
            listOf(
                awsModule,
                module {
                    single {
                        User(
                            email = "test@example.com",
                            region = "us-west-2",
                            keyName = "test-key",
                            awsProfile = "",
                            awsAccessKey = "test-access-key",
                            awsSecret = "test-secret-key",
                        )
                    }
                },
            )

        @Test
        fun `should use StaticCredentialsProvider when awsProfile is empty`() {
            val credentialsProvider: AwsCredentialsProvider by inject()

            assertThat(credentialsProvider).isInstanceOf(StaticCredentialsProvider::class.java)

            val credentials = credentialsProvider.resolveCredentials()
            assertThat(credentials.accessKeyId()).isEqualTo("test-access-key")
            assertThat(credentials.secretAccessKey()).isEqualTo("test-secret-key")
        }

        @Test
        fun `should provide EmrClient with correct configuration`() {
            val emrClient: EmrClient by inject()

            assertThat(emrClient).isInstanceOf(EmrClient::class.java)
        }
    }
}
