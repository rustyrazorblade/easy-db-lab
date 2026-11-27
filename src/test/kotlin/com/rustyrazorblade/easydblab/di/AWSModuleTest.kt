package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.TestProfiles
import com.rustyrazorblade.easydblab.configuration.profile.ProfileAccess
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.providers.aws.awsClientsModule
import com.rustyrazorblade.easydblab.services.aws.awsServicesModule
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
                awsClientsModule,
                awsServicesModule,
                module {
                    single<ProfileAccess> {
                        TestProfiles.accessOf(
                            TestProfiles.profile(region = "us-east-1", credentials = AwsCredentials.AwsProfile("test-profile")),
                        )
                    }
                },
            )

        // Control: build a static provider for every credential form; this fails.
        @Test
        fun `credentials provider follows the credential form - aws-profile gives a profile provider`() {
            val credentialsProvider: AwsCredentialsProvider by inject()

            assertThat(credentialsProvider).isInstanceOf(ProfileCredentialsProvider::class.java)
        }
    }

    @Nested
    inner class WithStaticCredentials : BaseKoinTest() {
        override fun coreTestModules(): List<Module> = emptyList()

        override fun additionalTestModules(): List<Module> =
            listOf(
                awsClientsModule,
                awsServicesModule,
                module {
                    single<ProfileAccess> {
                        TestProfiles.accessOf(
                            TestProfiles.profile(credentials = AwsCredentials.AccessKey("test-access-key", "test-secret-key")),
                        )
                    }
                },
            )

        // Control: build a profile provider for every credential form; this fails.
        @Test
        fun `credentials provider follows the credential form - access-key gives the static key`() {
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
