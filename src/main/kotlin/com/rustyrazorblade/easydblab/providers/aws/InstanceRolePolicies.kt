package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants

/**
 * The inline policies the EC2 instance role must carry: S3 access to the easy-db-lab buckets, and
 * the Session Manager permissions the SSM agent needs so any cluster node or AMI builder can be
 * reached over SSM.
 *
 * It is a separate collaborator, not more functions on [IamRoles], so the role's policy set is
 * defined in one place and [IamRoles] stays a thin SDK wrapper. Applying is idempotent, since
 * `PutRolePolicy` overwrites a policy of the same name, so the same call completes a new role and
 * upgrades one created by an older version.
 */
class InstanceRolePolicies(
    private val iamRoles: IamRoles,
) {
    /** Puts every inline policy in the set onto [roleName]. */
    fun apply(roleName: String) {
        iamRoles.attachS3Policy(roleName)
        putInlinePolicy(roleName, Constants.AWS.InlinePolicies.SESSION_MANAGER, AWSPolicy.Inline.SessionManagerInstance.toJson())
    }

    /**
     * The names of the inline policies in the set that [roleName] does not carry yet. One page of
     * `ListRolePolicies` (up to 100 names) is enough: the role carries only the policies this tool
     * puts on it.
     */
    fun missing(roleName: String): List<String> {
        val present = iamRoles.inlinePolicyNames(roleName)
        return REQUIRED.filterNot { it in present }
    }

    private fun putInlinePolicy(
        roleName: String,
        policyName: String,
        document: String,
    ) {
        iamRoles.putInlinePolicy(roleName, policyName, document)
    }

    private companion object {
        val REQUIRED = listOf(Constants.AWS.InlinePolicies.S3_ACCESS, Constants.AWS.InlinePolicies.SESSION_MANAGER)
    }
}
