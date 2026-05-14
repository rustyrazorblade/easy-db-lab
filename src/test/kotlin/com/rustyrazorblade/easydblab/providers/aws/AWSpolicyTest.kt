package com.rustyrazorblade.easydblab.providers.aws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for AWSPolicy serialization using IamPolicyDocument data classes.
 */
class AWSpolicyTest {
    @Nested
    inner class TrustPolicies {
        @Test
        fun `EC2Service should generate valid trust policy`() {
            val json = AWSPolicy.Trust.EC2Service.toJson()

            assertThat(json).contains(""""Version":"2012-10-17"""")
            assertThat(json).contains(""""Effect":"Allow"""")
            assertThat(json).contains(""""Service":"ec2.amazonaws.com"""")
            assertThat(json).contains(""""Action":"sts:AssumeRole"""")
            // Trust policies should not have Resource field
            assertThat(json).doesNotContain(""""Resource":""")
        }

        @Test
        fun `EMRService should generate valid trust policy`() {
            val json = AWSPolicy.Trust.EMRService.toJson()

            assertThat(json).contains(""""Version":"2012-10-17"""")
            assertThat(json).contains(""""Effect":"Allow"""")
            assertThat(json).contains(""""Service":"elasticmapreduce.amazonaws.com"""")
            assertThat(json).contains(""""Action":"sts:AssumeRole"""")
            // Trust policies should not have Resource field
            assertThat(json).doesNotContain(""""Resource":""")
        }
    }

    @Nested
    inner class InlinePolicies {
        @Test
        fun `S3AccessWildcard should generate valid policy with multiple statements`() {
            val json = AWSPolicy.Inline.S3AccessWildcard("123456789012").toJson()

            // First statement for ListAllMyBuckets
            assertThat(json).contains(""""Action":"s3:ListAllMyBuckets"""")
            assertThat(json).contains(""""Resource":"*"""")

            // Second statement for S3 access
            assertThat(json).contains(""""Action":"s3:*"""")
            assertThat(json).contains(""""arn:aws:s3:::easy-db-lab-*"""")
            assertThat(json).contains(""""arn:aws:s3:::easy-db-lab-*/*"""")

            // ECR repo-level actions scoped to account
            assertThat(json).contains(""""arn:aws:ecr:*:123456789012:repository/*"""")

            // Inline policies should not have Principal field
            assertThat(json).doesNotContain(""""Principal":""")
        }

        @Test
        fun `S3BucketPolicy should generate valid policy with multiple principals`() {
            val policy = AWSPolicy.Inline.S3BucketPolicy("123456789012", "test-bucket")
            val json = policy.toJson()

            // Check for multiple AWS principals
            assertThat(json).contains(""""AWS":[""")
            assertThat(json).contains(""""arn:aws:iam::123456789012:role/EasyDBLabEC2Role"""")
            assertThat(json).contains(""""arn:aws:iam::123456789012:role/EasyDBLabEMRServiceRole"""")
            assertThat(json).contains(""""arn:aws:iam::123456789012:role/EasyDBLabEMREC2Role"""")

            // Check action and resources
            assertThat(json).contains(""""Action":"s3:*"""")
            assertThat(json).contains(""""arn:aws:s3:::test-bucket"""")
            assertThat(json).contains(""""arn:aws:s3:::test-bucket/*"""")
        }
    }

    @Nested
    inner class IamPolicyDocumentSerialization {
        @Test
        fun `should serialize single AWS principal correctly`() {
            val policy =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                principal = IamPolicyPrincipal.aws("*"),
                                action = IamPolicyAction.single("es:*"),
                                resource = IamPolicyResource.single("arn:aws:es:us-west-2:123:domain/test/*"),
                            ),
                        ),
                )

            val json = policy.toJson()

            assertThat(json).contains(""""Principal":{"AWS":"*"}""")
            assertThat(json).contains(""""Action":"es:*"""")
            assertThat(json).contains(""""Resource":"arn:aws:es:us-west-2:123:domain/test/*"""")
        }

        @Test
        fun `should serialize multiple AWS principals as array`() {
            val policy =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                principal =
                                    IamPolicyPrincipal.aws(
                                        listOf("arn:aws:iam::123:role/Role1", "arn:aws:iam::123:role/Role2"),
                                    ),
                                action = IamPolicyAction.single("s3:*"),
                                resource = IamPolicyResource.single("*"),
                            ),
                        ),
                )

            val json = policy.toJson()

            assertThat(json).contains(""""AWS":["arn:aws:iam::123:role/Role1","arn:aws:iam::123:role/Role2"]""")
        }

        @Test
        fun `should serialize service principal correctly`() {
            val policy =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                principal = IamPolicyPrincipal.service("ec2.amazonaws.com"),
                                action = IamPolicyAction.single("sts:AssumeRole"),
                            ),
                        ),
                )

            val json = policy.toJson()

            assertThat(json).contains(""""Principal":{"Service":"ec2.amazonaws.com"}""")
            // Should not include null AWS field
            assertThat(json).doesNotContain(""""AWS":null""")
        }

        @Test
        fun `should serialize multiple resources as array`() {
            val policy =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                action = IamPolicyAction.single("s3:*"),
                                resource =
                                    IamPolicyResource.multiple(
                                        listOf("arn:aws:s3:::bucket", "arn:aws:s3:::bucket/*"),
                                    ),
                            ),
                        ),
                )

            val json = policy.toJson()

            assertThat(json).contains(""""Resource":["arn:aws:s3:::bucket","arn:aws:s3:::bucket/*"]""")
        }

        @Test
        fun `should omit null fields`() {
            val policy =
                IamPolicyDocument(
                    statement =
                        listOf(
                            IamPolicyStatement(
                                effect = "Allow",
                                action = IamPolicyAction.single("s3:*"),
                                resource = IamPolicyResource.single("*"),
                            ),
                        ),
                )

            val json = policy.toJson()

            // Should not include Principal field when it's null
            assertThat(json).doesNotContain(""""Principal"""")
        }
    }
}

private fun IamPolicyDocument.toJson(): String = IamPolicyDocument.Companion.run { this@toJson.toJson() }
