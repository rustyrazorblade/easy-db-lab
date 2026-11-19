package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import com.rustyrazorblade.easydblab.providers.aws.IamRoles
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.sts.model.StsException

/**
 * `show-iam-policies` needs the account ID. A failure fetching it propagates unchanged, and the
 * command failure reporter turns it into exactly one report: a provider error with the profile
 * setup remedy for expired credentials, the cause chain for anything else.
 */
class ShowIamPoliciesCredentialFailureTest : BaseKoinTest() {
    private var accountFailure: Exception = IllegalStateException("unset")
    private val events = mutableListOf<Event>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<AccountIdentity> {
                    object : AccountIdentity {
                        override fun getAccountId(): String = throw accountFailure

                        override fun checkPermissions() = Unit
                    }
                }
                single { AWSResourceSetupService(get<AccountIdentity>(), mock<IamRoles>(), get(), get(), AwsErrorHandler()) }
            },
        )

    @BeforeEach
    fun captureEvents() {
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
    }

    private fun runAndReport(): Throwable {
        val failure = catchThrowable { ShowIamPolicies().execute() }
        CommandFailureReporter(getKoin().get(), listOf(AwsErrorHandler())).report(failure)
        return failure
    }

    @Test
    fun `expired credentials are reported once as a credentials provider error`() {
        accountFailure =
            StsException
                .builder()
                .statusCode(403)
                .awsErrorDetails(
                    AwsErrorDetails
                        .builder()
                        .errorCode("ExpiredToken")
                        .errorMessage("expired")
                        .build(),
                ).message("expired")
                .build()

        assertThat(runAndReport()).isSameAs(accountFailure)

        assertThat(events.filter { it.isError() }).hasSize(1)
        val reported = events.single { it.isError() }
        assertThat(reported).isInstanceOf(Event.Command.ProviderError::class.java)
        assertThat((reported as Event.Command.ProviderError).kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
    }

    @Test
    fun `a failure that is not an AWS failure is reported once as an execution error`() {
        accountFailure = IllegalStateException("bug")

        assertThat(runAndReport()).isSameAs(accountFailure)

        assertThat(events.filter { it.isError() }).hasSize(1)
        assertThat(events.single { it.isError() }).isInstanceOf(Event.Command.ExecutionError::class.java)
    }
}
