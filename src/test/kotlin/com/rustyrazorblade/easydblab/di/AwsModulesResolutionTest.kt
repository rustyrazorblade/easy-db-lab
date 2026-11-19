package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import com.rustyrazorblade.easydblab.providers.aws.IamRoles
import com.rustyrazorblade.easydblab.providers.aws.RegionalS3Clients
import com.rustyrazorblade.easydblab.providers.aws.awsClientsModule
import com.rustyrazorblade.easydblab.providers.docker.DockerErrorHandler
import com.rustyrazorblade.easydblab.providers.docker.dockerModule
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import com.rustyrazorblade.easydblab.services.LokiQueryService
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import com.rustyrazorblade.easydblab.services.aws.CompactorLeaseReader
import com.rustyrazorblade.easydblab.services.aws.awsServicesModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.StsException

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
            dockerModule,
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
    fun `every SDK client, the credentials manager and the account and IAM ports resolve`() {
        assertThat(get<IamClient>()).isNotNull
        assertThat(get<Ec2Client>()).isNotNull
        assertThat(get<S3Client>()).isNotNull
        assertThat(get<StsClient>()).isNotNull
        assertThat(get<EmrClient>()).isNotNull
        assertThat(get<OpenSearchClient>()).isNotNull
        assertThat(get<ServiceQuotasClient>()).isNotNull
        assertThat(get<AccountIdentity>()).isNotNull
        assertThat(get<IamRoles>()).isNotNull
        assertThat(get<RegionalS3Clients>()).isNotNull
        assertThat(get<AWSClientFactory>()).isNotNull
        assertThat(get<AWSCredentialsManager>()).isNotSameAs(get<AWSCredentialsManager>())
    }

    @Test
    fun `the AWS services resolve`() {
        assertThat(get<SparkService>()).isNotNull
        assertThat(get<CompactorLeaseReader>()).isNotNull
    }

    @Test
    fun `the AWS and Docker error handlers are both registered provider handlers`() {
        assertThat(getKoin().getAll<ProviderErrorHandler>())
            .hasAtLeastOneElementOfType(AwsErrorHandler::class.java)
            .hasAtLeastOneElementOfType(DockerErrorHandler::class.java)
    }

    @Test
    fun `the reporter built from every registered handler reports an expired AWS token as a credentials error`() {
        val bus = get<EventBus>()
        val events = mutableListOf<Event>()
        bus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        val expired =
            StsException
                .builder()
                .statusCode(403)
                .awsErrorDetails(
                    AwsErrorDetails
                        .builder()
                        .errorCode("ExpiredToken")
                        .errorMessage("The security token included in the request is expired")
                        .build(),
                ).build()

        CommandFailureReporter(bus, getKoin().getAll()).report(expired)

        val error = events.single() as Event.Command.ProviderError
        assertThat(error.provider).isEqualTo("AWS")
        assertThat(error.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
    }

    @Test
    fun `the handlers resolve with no user profile bound`() {
        val app = koinApplication { modules(awsClientsModule, dockerModule) }
        try {
            val handlers = app.koin.getAll<ProviderErrorHandler>()

            assertThat(handlers)
                .hasAtLeastOneElementOfType(AwsErrorHandler::class.java)
                .hasAtLeastOneElementOfType(DockerErrorHandler::class.java)
        } finally {
            app.close()
        }
    }
}
