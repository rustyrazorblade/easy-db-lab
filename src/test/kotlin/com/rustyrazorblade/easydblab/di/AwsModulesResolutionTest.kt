package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import com.rustyrazorblade.easydblab.providers.aws.awsClientsModule
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import com.rustyrazorblade.easydblab.services.LokiQueryService
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import com.rustyrazorblade.easydblab.services.aws.awsServicesModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecr.EcrClient
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

/**
 * The two production AWS modules wired together. Koin resolves bindings lazily and `getAll()`
 * collects handlers only at the call, so a mistyped binding in the split would otherwise surface
 * as a crash on a user's first command.
 */
internal class AwsModulesResolutionTest : BaseKoinTest() {
    override fun coreTestModules(): List<Module> = emptyList()

    override fun additionalTestModules(): List<Module> =
        listOf(
            awsClientsModule,
            awsServicesModule,
            module {
                single { EventBus() }
                single {
                    com.rustyrazorblade.easydblab.TestContextFactory
                        .createTestContext(tempDir)
                }
                single {
                    User(
                        email = "t@example.com",
                        region = "us-west-2",
                        keyName = "k",
                        awsProfile = "",
                        awsAccessKey = "a",
                        awsSecret = "s",
                    )
                }
                single<ClusterStateManager> { mock() }
                single<ObjectStore> { mock() }
                single<LokiQueryService> { mock() }
                single<com.rustyrazorblade.easydblab.services.ExternalIpService> { mock() }
            },
        )

    @Test
    fun `every SDK client, the credentials manager and the AWS wrapper resolve`() {
        assertThat(get<IamClient>()).isNotNull
        assertThat(get<Ec2Client>()).isNotNull
        assertThat(get<S3Client>()).isNotNull
        assertThat(get<StsClient>()).isNotNull
        assertThat(get<EmrClient>()).isNotNull
        assertThat(get<OpenSearchClient>()).isNotNull
        assertThat(get<EcrClient>()).isNotNull
        assertThat(get<AWS>()).isNotNull
        assertThat(get<AWSClientFactory>()).isNotNull
        assertThat(get<AWSCredentialsManager>()).isNotSameAs(get<AWSCredentialsManager>())
    }

    @Test
    fun `the AWS services resolve`() {
        assertThat(get<SparkService>()).isNotNull
    }

    @Test
    fun `the AWS error handler is the registered provider handler`() {
        assertThat(getKoin().getAll<ProviderErrorHandler>()).hasAtLeastOneElementOfType(AwsErrorHandler::class.java)
    }

    @Test
    fun `the reporter built from every registered handler resolves`() {
        val reporter = CommandFailureReporter(get(), getKoin().getAll())

        assertThat(reporter).isNotNull
    }

    @Test
    fun `the handlers resolve with no user profile bound`() {
        val app = koinApplication { modules(awsClientsModule) }
        try {
            val handlers = app.koin.getAll<ProviderErrorHandler>()

            assertThat(handlers).hasAtLeastOneElementOfType(AwsErrorHandler::class.java)
        } finally {
            app.close()
        }
    }
}
