package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ecr.model.EcrException
import software.amazon.awssdk.services.ecs.model.EcsException
import software.amazon.awssdk.services.emr.model.EmrException
import software.amazon.awssdk.services.iam.model.IamException
import software.amazon.awssdk.services.opensearch.model.OpenSearchException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.ssm.model.SsmException

/**
 * Tells the command layer which AWS failures are a credentials or a permissions problem, and what
 * the user should do about each.
 *
 * It claims an exception by its type and error code alone and takes no dependencies, so it works
 * before a profile exists (`init`, `profile setup`). The error code is checked before the HTTP
 * status, because STS, IAM and S3 answer 403 for bad credentials. Anything else, including a
 * network `SdkClientException`, returns null and is reported as an ordinary execution error.
 */
class AwsErrorHandler : ProviderErrorHandler {
    override fun describe(error: Throwable): ErrorDescription? =
        when (error) {
            is AwsServiceException -> describeService(error)
            is SdkClientException -> describeClient(error)
            else -> null
        }

    private fun describeService(error: AwsServiceException): ErrorDescription? {
        val code = error.awsErrorDetails()?.errorCode()
        val detail = error.awsErrorDetails()?.errorMessage() ?: error.message.orEmpty()
        val kind =
            when {
                code in Constants.AWS.ErrorCodes.CREDENTIALS -> ProviderErrorKind.CREDENTIALS
                code in Constants.AWS.ErrorCodes.PERMISSION_DENIED -> ProviderErrorKind.PERMISSION_DENIED
                code == Constants.AWS.ErrorCodes.CLOCK_SKEW -> null
                error.statusCode() == Constants.HttpStatus.FORBIDDEN -> ProviderErrorKind.PERMISSION_DENIED
                else -> null
            }
        return kind?.let { describeAs(it, detail, error) }
    }

    private fun describeClient(error: SdkClientException): ErrorDescription? {
        val message = error.message.orEmpty()
        return if (Constants.AWS.ErrorCodes.CREDENTIALS_MESSAGES.any { message.contains(it) }) {
            describeAs(ProviderErrorKind.CREDENTIALS, message, error)
        } else {
            null
        }
    }

    private fun describeAs(
        kind: ProviderErrorKind,
        detail: String,
        error: Throwable,
    ): ErrorDescription =
        ErrorDescription(
            provider = PROVIDER,
            kind = kind,
            detail = detail,
            remedy =
                when (kind) {
                    ProviderErrorKind.CREDENTIALS -> CREDENTIALS_REMEDY
                    ProviderErrorKind.PERMISSION_DENIED -> permissionRemedy(policyFor(error))
                },
        )

    private fun permissionRemedy(policy: String?): String =
        if (policy != null) {
            "Attach the $policy IAM policy to your AWS user. Print it with: easy-db-lab show-iam-policies $policy"
        } else {
            "Attach the lab IAM policies (${ALL_POLICIES.joinToString(", ")}) to your AWS user. " +
                "Print them with: easy-db-lab show-iam-policies"
        }

    private fun policyFor(error: Throwable): String? =
        when (error) {
            is Ec2Exception, is EcrException, is SsmException -> AWSPolicy.UserIAM.EC2_POLICY
            is IamException, is S3Exception -> AWSPolicy.UserIAM.IAM_POLICY
            is EmrException -> AWSPolicy.UserIAM.EMR_POLICY
            is OpenSearchException -> AWSPolicy.UserIAM.OPENSEARCH_POLICY
            is EcsException, is CloudWatchLogsException -> AWSPolicy.UserIAM.COMPACTOR_POLICY
            else -> null
        }

    private companion object {
        const val PROVIDER = "AWS"

        const val CREDENTIALS_REMEDY =
            "For an SSO profile run: aws sso login --profile <profile>. Otherwise run: easy-db-lab profile setup. " +
                "Check the result with: aws sts get-caller-identity"

        val ALL_POLICIES =
            listOf(
                AWSPolicy.UserIAM.EC2_POLICY,
                AWSPolicy.UserIAM.IAM_POLICY,
                AWSPolicy.UserIAM.EMR_POLICY,
                AWSPolicy.UserIAM.OPENSEARCH_POLICY,
                AWSPolicy.UserIAM.COMPACTOR_POLICY,
            )
    }
}
