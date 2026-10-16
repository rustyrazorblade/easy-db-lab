package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.EasyDBLabCommand
import com.rustyrazorblade.easydblab.commands.observability.ObservabilityExport
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.localstack.localStackModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import picocli.CommandLine
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest
import software.amazon.awssdk.services.ssooidc.model.ExpiredTokenException
import java.io.File

/** `observability export` is reachable: the real [exportModule] builds the exporter, and the root command routes the command. */
class ExportModuleTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            localStackModule,
            exportModule,
            module {
                single { GrafanaDashboardCatalog.discover() }
                single<AwsCredentialsProvider> { mock() }
                // Not a cluster workspace.
                single<ClusterStateManager> { mock { on { exists() } doReturn false } }
            },
        )

    @Test
    fun `the module builds the tenant exporter`() {
        assertThatCode { getKoin().get<TenantExporter>() }.doesNotThrowAnyException()
    }

    @Test
    fun `the root command routes observability export with an experiment and its tenant`() {
        val parsed =
            CommandLine(
                EasyDBLabCommand(),
            ).parseArgs("observability", "export", "out.zip", "--experiment", "e", "--tenant", "acme")

        val command = generateSequence(parsed) { it.subcommand() }.last().commandSpec().userObject() as ObservabilityExport
        assertThat(command.path.path).isEqualTo("out.zip")
        assertThat(command.selector.choice().experiment).isEqualTo("e")
        assertThat(command.selector.choice().tenant).isEqualTo("acme")
    }

    /**
     * The live check's failure: with an expired SSO session, the SDK's `ExpiredTokenException` reached
     * the operator raw. Through the real wiring, the export now reports the profile, the AWS error and
     * the login command, and writes nothing at all, not even a partial file.
     */
    @Test
    fun `an expired SSO session fails the export with the profile and the login hint, and writes nothing`() {
        get<User>().apply {
            awsProfile = "lab-profile"
            s3Bucket = "acct"
        }
        whenever(get<S3Client>().getBucketLocation(any<GetBucketLocationRequest>()))
            .thenThrow(ExpiredTokenException.builder().message("The SSO session associated with this profile has expired").build())
        val events = mutableListOf<Event>()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
        val out = File(tempDir, "exports").apply { mkdirs() }

        assertThatThrownBy {
            ObservabilityExport().also { CommandLine(it).parseArgs(File(out, "acme.zip").path, "--tenant", "acme") }.execute()
        }.isInstanceOf(CommandFailedException::class.java)

        val unreadable = events.filterIsInstance<Event.Export.StoreUnreadable>().single()
        assertThat(unreadable.profile).isEqualTo("lab-profile")
        assertThat(unreadable.awsErrorCode).startsWith("ExpiredTokenException")
        assertThat(unreadable.toDisplayString()).contains("aws sso login --profile lab-profile")
        assertThat(out.list()).isEmpty()
    }
}
