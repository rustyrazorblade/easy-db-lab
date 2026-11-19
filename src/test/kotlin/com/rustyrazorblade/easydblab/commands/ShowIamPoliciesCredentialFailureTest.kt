package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.IamRoles
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sts.model.StsException
import java.util.stream.Stream

/**
 * `show-iam-policies` needs the account ID. An AWS failure fetching it is reported as a credential
 * problem and still fails the command; a failure that is not an AWS failure is not mistaken for one.
 */
class ShowIamPoliciesCredentialFailureTest : BaseKoinTest() {
    private var accountFailure: Exception = IllegalStateException("unset")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<AccountIdentity> {
                    object : AccountIdentity {
                        override fun getAccountId(): String = throw accountFailure

                        override fun checkPermissions() = Unit
                    }
                }
                single { AWSResourceSetupService(get<AccountIdentity>(), mock<IamRoles>(), get(), get()) }
            },
        )

    companion object {
        @JvmStatic
        fun awsFailures(): Stream<Exception> =
            Stream.of(
                SdkClientException.create("Unable to load credentials"),
                StsException.builder().message("ExpiredToken").build(),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("awsFailures")
    fun `an AWS failure fetching the account ID is reported as a credential error and rethrown`(failure: Exception) {
        accountFailure = failure
        val outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        assertThatThrownBy { ShowIamPolicies().execute() }.isSameAs(failure)

        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("Failed to get AWS account ID")
    }

    @Test
    fun `a failure that is not an AWS failure propagates without a credential error`() {
        accountFailure = IllegalStateException("bug")
        val outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        assertThatThrownBy { ShowIamPolicies().execute() }.isSameAs(accountFailure)

        assertThat(outputHandler.errors).isEmpty()
    }
}
