package com.rustyrazorblade.easydblab.providers.aws

/**
 * The result of validating the IAM role setup.
 *
 * @property isValid True if all required components exist and are properly configured
 * @property instanceProfileExists True if the instance profile exists
 * @property roleAttached True if the role is attached to the instance profile
 * @property hasPolicies True if the role has inline policies attached
 * @property errorMessage Optional error message if validation failed
 */
data class RoleValidationResult(
    val isValid: Boolean,
    val instanceProfileExists: Boolean,
    val roleAttached: Boolean,
    val hasPolicies: Boolean,
    val errorMessage: String? = null,
)

/**
 * The IAM operations easy-db-lab needs: its roles, instance profiles, inline policies and the
 * OpenSearch service-linked role. These are low-level SDK operations; the order they run in and the
 * user-facing messages belong to [com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService].
 */
interface IamRoles {
    /** Creates the IAM role for the EMR service and attaches its managed policy. Idempotent. */
    fun createServiceRole(): String

    /** Creates the IAM role and instance profile for EMR EC2 instances. Idempotent. */
    fun createEMREC2Role(): String

    /**
     * Creates an IAM role with an EC2 trust policy, its inline S3 policy and its instance profile,
     * then validates the result. Idempotent.
     *
     * @throws IllegalArgumentException if the role name is invalid
     * @throws IllegalStateException if the role fails validation afterwards
     */
    fun createRoleWithS3Policy(roleName: String): String

    /** Attaches the inline S3 access policy for all easy-db-lab buckets to [roleName]. */
    fun attachS3Policy(roleName: String)

    /** Whether the instance profile named [roleName] exists. */
    fun roleExists(roleName: String): Boolean

    /** Validates that [roleName] has an instance profile with the role attached and inline policies. */
    fun validateRoleSetup(roleName: String): RoleValidationResult

    /** Ensures the OpenSearch service-linked role exists. */
    fun ensureOpenSearchServiceLinkedRole()

    /** Puts the inline policy [policyName] with [document] on [roleName], replacing one of the same name. */
    fun putInlinePolicy(
        roleName: String,
        policyName: String,
        document: String,
    )

    /**
     * The names of the inline policies on [roleName]. One page of `ListRolePolicies` (up to 100
     * names) is enough: the role carries only the policies this tool puts on it.
     */
    fun inlinePolicyNames(roleName: String): Set<String>
}
