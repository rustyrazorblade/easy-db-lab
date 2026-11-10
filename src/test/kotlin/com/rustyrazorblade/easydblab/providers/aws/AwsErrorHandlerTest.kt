package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ecr.model.EcrException
import software.amazon.awssdk.services.ecs.model.EcsException
import software.amazon.awssdk.services.emr.model.EmrException
import software.amazon.awssdk.services.iam.model.IamException
import software.amazon.awssdk.services.opensearch.model.OpenSearchException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.ssm.model.SsmException
import software.amazon.awssdk.services.sts.model.StsException
import java.io.IOException
import java.io.UncheckedIOException

/**
 * Classification of AWS failures, built from real SDK exceptions so an SDK upgrade that rewords a
 * message or renames an error code fails here instead of at a user's terminal.
 */
class AwsErrorHandlerTest {
    private val handler = AwsErrorHandler()

    private fun awsError(
        builder: AwsServiceException.Builder,
        code: String,
        status: Int,
    ): AwsServiceException =
        builder
            .statusCode(status)
            .awsErrorDetails(
                AwsErrorDetails
                    .builder()
                    .errorCode(code)
                    .errorMessage("$code happened")
                    .build(),
            ).message("$code happened")
            .build() as AwsServiceException

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ExpiredToken", "ExpiredTokenException", "RequestExpired", "AuthFailure", "InvalidClientTokenId",
            "UnrecognizedClientException", "InvalidAccessKeyId", "SignatureDoesNotMatch",
            "InvalidSignatureException", "InvalidToken", "TokenRefreshRequired", "MissingAuthenticationToken",
            "IncompleteSignature",
        ],
    )
    fun `credential error codes are credentials even when the status is 403`(code: String) {
        val description = handler.describe(awsError(StsException.builder(), code, 403))

        assertThat(description).isNotNull
        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
        assertThat(description.provider).isEqualTo("AWS")
        assertThat(description.detail).contains(code)
        assertThat(description.remedy).contains("aws sso login").contains("easy-db-lab profile setup")
    }

    @ParameterizedTest
    @ValueSource(strings = ["AccessDenied", "AccessDeniedException", "UnauthorizedOperation"])
    fun `permission error codes are permission denied`(code: String) {
        // AccessDeniedException arrives as a 400 from some services, so the status must not matter.
        val description = handler.describe(awsError(S3Exception.builder(), code, 400))

        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
    }

    @Test
    fun `any other 403 is permission denied`() {
        val description = handler.describe(awsError(Ec2Exception.builder(), "SomethingNew", 403))

        assertThat(description!!.kind).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
    }

    @Test
    fun `clock skew 403 is not claimed`() {
        assertThat(handler.describe(awsError(S3Exception.builder(), "RequestTimeTooSkewed", 403))).isNull()
    }

    @Test
    fun `a non 403 service error with an unknown code is not claimed`() {
        assertThat(handler.describe(awsError(Ec2Exception.builder(), "InvalidParameterValue", 400))).isNull()
    }

    @Test
    fun `missing credentials from a real profile provider are credentials`() {
        val emptyProfile =
            ProfileFile
                .builder()
                .content("[default]\n")
                .type(ProfileFile.Type.CREDENTIALS)
                .build()
        val provider =
            ProfileCredentialsProvider
                .builder()
                .profileFile(emptyProfile)
                .profileName("default")
                .build()
        val error = runCatching { provider.resolveCredentials() }.exceptionOrNull()

        assertThat(error).isInstanceOf(SdkClientException::class.java)
        assertThat(handler.describe(error!!)!!.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
    }

    @Test
    fun `an expired SSO session message is credentials`() {
        val error =
            SdkClientException
                .builder()
                .message("The SSO session associated with this profile has expired or is otherwise invalid.")
                .build()

        assertThat(handler.describe(error)!!.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
    }

    @Test
    fun `a network SdkClientException is not claimed`() {
        val error =
            SdkClientException
                .builder()
                .message("Unable to execute HTTP request: Connect timed out")
                .cause(UncheckedIOException(IOException("timeout")))
                .build()

        assertThat(handler.describe(error)).isNull()
    }

    @Test
    fun `a non AWS exception is not claimed`() {
        assertThat(handler.describe(IllegalStateException("boom"))).isNull()
    }

    @Test
    fun `the remedy names the policy that grants the failing service`() {
        val cases =
            listOf(
                Ec2Exception.builder() to "EasyDBLabEC2",
                EcrException.builder() to "EasyDBLabEC2",
                SsmException.builder() to "EasyDBLabEC2",
                IamException.builder() to "EasyDBLabIAM",
                S3Exception.builder() to "EasyDBLabIAM",
                EmrException.builder() to "EasyDBLabEMR",
                OpenSearchException.builder() to "EasyDBLabOpenSearch",
                EcsException.builder() to "EasyDBLabCompactor",
                CloudWatchLogsException.builder() to "EasyDBLabCompactor",
            )
        for ((builder, policy) in cases) {
            val description = handler.describe(awsError(builder, "AccessDenied", 403))!!
            assertThat(description.remedy)
                .describedAs(builder.javaClass.name)
                .contains("show-iam-policies $policy")
        }
    }

    @Test
    fun `an STS permission error lists every policy`() {
        val description = handler.describe(awsError(StsException.builder(), "AccessDenied", 403))!!

        assertThat(description.remedy)
            .contains("EasyDBLabEC2", "EasyDBLabIAM", "EasyDBLabEMR", "EasyDBLabOpenSearch", "EasyDBLabCompactor")
            .contains("show-iam-policies")
    }
}
