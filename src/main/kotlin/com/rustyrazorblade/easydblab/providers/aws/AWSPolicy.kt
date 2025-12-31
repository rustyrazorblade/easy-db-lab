package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Policy
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.aws.IamPolicyDocument.Companion.toJson

/**
 * Centralized AWS policy definitions for easy-db-lab.
 * Provides type-safe access to all AWS policies used throughout the application.
 *
 * ## Policy Categories
 * - **Trust Policies**: Define which AWS services can assume IAM roles
 * - **User IAM Policies**: Policies users need to create easy-db-lab resources
 * - **Inline Policies**: Runtime-generated policies attached to IAM roles
 * - **Managed Policies**: References to AWS-managed policy ARNs
 *
 * ## Usage Examples
 * ```kotlin
 * // Trust policy for EC2 service
 * val trustPolicy = AWSPolicy.Trust.EC2Service.toJson()
 *
 * // Inline S3 access policy
 * val s3Policy = AWSPolicy.Inline.S3Access("my-bucket").toJson()
 *
 * // AWS managed policy ARN
 * val managedArn = AWSPolicy.Managed.EMRServiceRole.arn
 *
 * // User IAM policies with account ID substitution
 * val userPolicies = AWSPolicy.UserIAM.loadAll("123456789012")
 * ```
 */
sealed class AWSPolicy {
    /**
     * Generates the JSON representation of this policy.
     * @return Policy document as JSON string
     * @throws UnsupportedOperationException for managed policies (use ARN instead)
     */
    abstract fun toJson(): String

    /**
     * Trust policies (assume role policies) that define which AWS services can assume IAM roles.
     * These policies are static and don't require variable substitution.
     */
    sealed class Trust : AWSPolicy() {
        /**
         * Trust policy allowing EC2 service to assume the role.
         * Used by: EasyDBLabEC2Role, EasyDBLabEMREC2Role
         */
        data object EC2Service : Trust() {
            override fun toJson() =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                principal = IamPolicyPrincipal.service("ec2.amazonaws.com"),
                                action = IamPolicyAction.single("sts:AssumeRole"),
                            ),
                        ),
                ).toJson()
        }

        /**
         * Trust policy allowing EMR service to assume the role.
         * Used by: EasyDBLabEMRServiceRole
         */
        data object EMRService : Trust() {
            override fun toJson() =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                principal = IamPolicyPrincipal.service("elasticmapreduce.amazonaws.com"),
                                action = IamPolicyAction.single("sts:AssumeRole"),
                            ),
                        ),
                ).toJson()
        }
    }

    /**
     * User IAM policies that users must attach to their AWS account to use easy-db-lab.
     * These policies are loaded from JSON resource files with account ID substitution.
     *
     * Three policies are required:
     * 1. EasyDBLabEC2 - EC2 operations and PassRole
     * 2. EasyDBLabIAM - IAM and S3 management
     * 3. EasyDBLabEMR - EMR cluster operations
     */
    sealed class UserIAM : AWSPolicy() {
        override fun toJson(): String =
            throw UnsupportedOperationException("Use loadAll() to get user policies with account ID substitution")

        companion object {
            /**
             * Loads all three required user IAM policies from JSON resource files.
             * Performs account ID substitution in policy templates.
             *
             * @param accountId The AWS account ID to substitute for ACCOUNT_ID placeholder
             * @return List of Policy objects with name and processed JSON content
             * @throws IllegalStateException if unable to load policy template files
             */
            fun loadAll(accountId: String): List<Policy> {
                val policyData =
                    listOf(
                        "iam-policy-ec2.json" to "EasyDBLabEC2",
                        "iam-policy-iam-s3.json" to "EasyDBLabIAM",
                        "iam-policy-emr.json" to "EasyDBLabEMR",
                        "iam-policy-opensearch.json" to "EasyDBLabOpenSearch",
                    )

                return policyData.map { (fileName, policyName) ->
                    val policyStream =
                        User::class.java.getResourceAsStream("/com/rustyrazorblade/easydblab/$fileName")
                    val policyContent =
                        policyStream?.bufferedReader()?.use { it.readText() }
                            ?: error("Unable to load IAM policy template: $fileName")

                    // Replace ACCOUNT_ID placeholder with actual account ID
                    val processedContent = policyContent.replace("ACCOUNT_ID", accountId)

                    Policy(name = policyName, body = processedContent)
                }
            }
        }
    }

    /**
     * Inline policies generated at runtime with variable substitution.
     * These policies are attached directly to IAM roles and require dynamic values.
     */
    sealed class Inline : AWSPolicy() {
        /**
         * S3 and SQS access policy granting full S3 access to all easy-db-lab buckets
         * and SQS read access for log ingestion queues.
         * Uses wildcard pattern to support per-environment buckets.
         * Attached to IAM roles to allow EC2 instances to access cluster data.
         * Also includes s3:ListAllMyBuckets for S3Manager web UI.
         */
        data object S3AccessWildcard : Inline() {
            override fun toJson() =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                action = IamPolicyAction.single("s3:ListAllMyBuckets"),
                                resource = IamPolicyResource.single("*"),
                            ),
                            IamPolicyStatement(
                                effect = "Allow",
                                action = IamPolicyAction.single("s3:*"),
                                resource =
                                    IamPolicyResource.multiple(
                                        listOf(
                                            "arn:aws:s3:::easy-db-lab-*",
                                            "arn:aws:s3:::easy-db-lab-*/*",
                                        ),
                                    ),
                            ),
                            IamPolicyStatement(
                                effect = "Allow",
                                action =
                                    IamPolicyAction.multiple(
                                        listOf(
                                            "sqs:ReceiveMessage",
                                            "sqs:DeleteMessage",
                                            "sqs:GetQueueAttributes",
                                            "sqs:GetQueueUrl",
                                        ),
                                    ),
                                resource = IamPolicyResource.single("arn:aws:sqs:*:*:easy-db-lab-*"),
                            ),
                        ),
                ).toJson()
        }

        /**
         * S3 bucket policy granting access to all three easy-db-lab IAM roles.
         * Applied to the S3 bucket to allow all roles (EC2, EMR Service, EMR EC2) to access it.
         *
         * @param accountId The AWS account ID for constructing role ARNs
         * @param bucketName The S3 bucket name to apply the policy to
         */
        data class S3BucketPolicy(
            val accountId: String,
            val bucketName: String,
        ) : Inline() {
            override fun toJson() =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                principal =
                                    IamPolicyPrincipal.aws(
                                        listOf(
                                            "arn:aws:iam::$accountId:role/${Constants.AWS.Roles.EC2_INSTANCE_ROLE}",
                                            "arn:aws:iam::$accountId:role/${Constants.AWS.Roles.EMR_SERVICE_ROLE}",
                                            "arn:aws:iam::$accountId:role/${Constants.AWS.Roles.EMR_EC2_ROLE}",
                                        ),
                                    ),
                                action = IamPolicyAction.single("s3:*"),
                                resource =
                                    IamPolicyResource.multiple(
                                        listOf(
                                            "arn:aws:s3:::$bucketName",
                                            "arn:aws:s3:::$bucketName/*",
                                        ),
                                    ),
                            ),
                        ),
                ).toJson()
        }
    }

    /**
     * AWS managed policy ARNs (not JSON documents, just references).
     * These are pre-existing AWS policies that can be attached to IAM roles.
     */
    sealed class Managed(
        val arn: String,
    ) : AWSPolicy() {
        /**
         * AWS managed policy for EMR service role.
         * Provides permissions necessary for EMR service to create and manage clusters.
         */
        data object EMRServiceRole :
            Managed("arn:aws:iam::aws:policy/service-role/AmazonElasticMapReduceRole")

        /**
         * AWS managed policy for EMR EC2 instances.
         * Provides permissions necessary for EC2 instances in EMR clusters (Spark workers).
         */
        data object EMRForEC2 :
            Managed("arn:aws:iam::aws:policy/service-role/AmazonElasticMapReduceforEC2Role")

        override fun toJson(): String =
            throw UnsupportedOperationException(
                "Managed policies don't have JSON content. Use the 'arn' property instead.",
            )
    }
}
