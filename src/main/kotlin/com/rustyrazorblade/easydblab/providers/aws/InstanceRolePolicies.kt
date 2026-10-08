package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest

/**
 * The inline policies the EC2 instance role must carry: S3 access to the easy-db-lab buckets, and
 * the Session Manager permissions the SSM agent needs so any cluster node or AMI builder can be
 * reached over SSM.
 *
 * It is a separate collaborator, not more functions on [AWS], so the role's policy set is
 * defined in one place and [AWS] stays a thin SDK wrapper. Applying is idempotent, since
 * `PutRolePolicy` overwrites a policy of the same name, so the same call completes a new role and
 * upgrades one created by an older version.
 */
class InstanceRolePolicies(
    private val aws: AWS,
) {
    /** Puts every inline policy in the set onto [roleName]. */
    fun apply(roleName: String) {
        aws.attachS3Policy(roleName)
        putInlinePolicy(roleName, Constants.AWS.InlinePolicies.SESSION_MANAGER, AWSPolicy.Inline.SessionManagerInstance.toJson())
    }

    /**
     * The names of the inline policies in the set that [roleName] does not carry yet. One page of
     * `ListRolePolicies` (up to 100 names) is enough: the role carries only the policies this tool
     * puts on it.
     */
    fun missing(roleName: String): List<String> {
        val present =
            aws.iamClient
                .listRolePolicies(ListRolePoliciesRequest.builder().roleName(roleName).build())
                .policyNames()
                .toSet()
        return REQUIRED.filterNot { it in present }
    }

    private fun putInlinePolicy(
        roleName: String,
        policyName: String,
        document: String,
    ) {
        Retry
            .decorateRunnable(Retry.of("put-role-policy-$roleName-$policyName", RetryUtil.createIAMRetryConfig())) {
                aws.iamClient.putRolePolicy(
                    PutRolePolicyRequest
                        .builder()
                        .roleName(roleName)
                        .policyName(policyName)
                        .policyDocument(document)
                        .build(),
                )
            }.run()
    }

    private companion object {
        val REQUIRED = listOf(Constants.AWS.InlinePolicies.S3_ACCESS, Constants.AWS.InlinePolicies.SESSION_MANAGER)
    }
}
