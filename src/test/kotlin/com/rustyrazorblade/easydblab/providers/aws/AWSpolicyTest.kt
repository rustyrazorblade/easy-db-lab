package com.rustyrazorblade.easydblab.providers.aws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

        /**
         * The cluster must not be able to delete observability data: the instance role is denied
         * deletes under mimir/, loki/, tempo/ and grafana/. pyroscope/ is left out because
         * Pyroscope v2 compaction deletes the segments it merged.
         */
        @Test
        fun `S3AccessWildcard denies deletes on every observability prefix but profiles`() {
            val statements =
                Json
                    .parseToJsonElement(AWSPolicy.Inline.S3AccessWildcard("123456789012").toJson())
                    .jsonObject
                    .getValue("Statement")
                    .jsonArray
                    .map { it.jsonObject }
            val deny = statements.single { it.getValue("Effect").jsonPrimitive.content == "Deny" }

            fun values(key: String) =
                when (val element = deny.getValue(key)) {
                    is JsonArray -> element.map { it.jsonPrimitive.content }
                    else -> listOf(element.jsonPrimitive.content)
                }

            assertThat(values("Action")).containsExactlyInAnyOrder("s3:DeleteObject", "s3:DeleteObjectVersion")
            assertThat(values("Resource")).containsExactlyInAnyOrder(
                "arn:aws:s3:::easy-db-lab-*/mimir/*",
                "arn:aws:s3:::easy-db-lab-*/loki/*",
                "arn:aws:s3:::easy-db-lab-*/tempo/*",
                "arn:aws:s3:::easy-db-lab-*/grafana/*",
                "arn:aws:s3:::easy-db-lab-*/reports/*",
                "arn:aws:s3:::easy-db-lab-*/*/mimir/*",
                "arn:aws:s3:::easy-db-lab-*/*/loki/*",
                "arn:aws:s3:::easy-db-lab-*/*/tempo/*",
                "arn:aws:s3:::easy-db-lab-*/*/grafana/*",
                "arn:aws:s3:::easy-db-lab-*/*/reports/*",
            )
            assertThat(values("Resource")).noneMatch { it.contains("pyroscope") }
            // Only deletes are denied: the cluster still reads the documents and the owner uploads them.
            assertThat(statements.toString()).doesNotContain("s3:PutObject")
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

        /** Every cluster-side principal the bucket policy grants is denied deletes under the compacted roots. */
        @Test
        fun `S3BucketPolicy denies the cluster roles every delete under the compacted roots`() {
            val deny =
                statements(AWSPolicy.Inline.S3BucketPolicy("123456789012", "test-bucket").toJson()).single {
                    it.effect() ==
                        "Deny"
                }

            assertThat(deny.values("Action")).containsExactlyInAnyOrder("s3:DeleteObject", "s3:DeleteObjectVersion")
            assertThat(deny.values("Resource")).containsExactlyInAnyOrder(
                "arn:aws:s3:::test-bucket/mimir/*",
                "arn:aws:s3:::test-bucket/loki/*",
                "arn:aws:s3:::test-bucket/tempo/*",
                "arn:aws:s3:::test-bucket/*/mimir/*",
                "arn:aws:s3:::test-bucket/*/loki/*",
                "arn:aws:s3:::test-bucket/*/tempo/*",
            )
            assertThat(deny.getValue("Principal").jsonObject.values("AWS")).containsExactlyInAnyOrder(
                "arn:aws:iam::123456789012:role/EasyDBLabEC2Role",
                "arn:aws:iam::123456789012:role/EasyDBLabEMRServiceRole",
                "arn:aws:iam::123456789012:role/EasyDBLabEMREC2Role",
            )
        }

        /** Only the compacted roots: the compactor's task role has no access to grafana/ or pyroscope/. */
        @Test
        fun `the compactor task role deletes only under mimir, loki and tempo`() {
            val statements = statements(AWSPolicy.Inline.CompactorTaskAccess("").toJson())
            val objects = statements.single { "s3:DeleteObject" in it.values("Action") }

            assertThat(objects.values("Resource")).containsExactlyInAnyOrder(
                "arn:aws:s3:::easy-db-lab-*/mimir/*",
                "arn:aws:s3:::easy-db-lab-*/loki/*",
                "arn:aws:s3:::easy-db-lab-*/tempo/*",
            )
            assertThat(statements.flatMap { it.values("Resource") }).noneMatch { it.contains("grafana") || it.contains("pyroscope") }
            assertThat(statements).allMatch { it.effect() == "Allow" }
        }
    }

    /**
     * The denies take no prefix: each is put whole, so whoever puts one last (`up` on any prefix, or
     * `profile setup`) puts the same deny. It must therefore cover every root under any prefix, so a
     * cluster on one prefix never drops the guard a cluster on another prefix, or on none, relies on.
     */
    @Nested
    inner class StorePrefix {
        private val prefix = "team-a"

        private fun instanceDeny() = statements(AWSPolicy.Inline.S3AccessWildcard("123456789012").toJson()).single { it.effect() == "Deny" }

        private fun bucketDeny() =
            statements(AWSPolicy.Inline.S3BucketPolicy("123456789012", "easy-db-lab-test").toJson()).single {
                it.effect() == "Deny"
            }

        @Test
        fun `the instance role deny covers every denied root under any prefix`() {
            val resources = instanceDeny().values("Resource")

            listOf("mimir", "loki", "tempo", "grafana", "reports").forEach { root ->
                listOf("$root/x", "team-a/$root/x", "team-b/nested/$root/x").forEach { key ->
                    assertThat(IamResourceMatcher.anyCovers(resources, "easy-db-lab-acct", key)).describedAs(key).isTrue()
                }
            }
            assertThat(IamResourceMatcher.anyCovers(resources, "easy-db-lab-acct", "pyroscope/x")).isFalse()
            assertThat(IamResourceMatcher.anyCovers(resources, "easy-db-lab-acct", "team-a/pyroscope/x")).isFalse()
            assertThat(IamResourceMatcher.anyCovers(resources, "easy-db-lab-acct", "clusters/lab-1/state.json")).isFalse()
        }

        @Test
        fun `the bucket policy deny covers every compacted root under any prefix`() {
            val resources = bucketDeny().values("Resource")

            listOf("mimir", "loki", "tempo").forEach { root ->
                listOf("$root/x", "team-a/$root/x", "team-b/nested/$root/x").forEach { key ->
                    assertThat(IamResourceMatcher.anyCovers(resources, "easy-db-lab-test", key)).describedAs(key).isTrue()
                }
            }
            listOf("grafana/x", "reports/x", "pyroscope/x", "team-a/grafana/x").forEach { key ->
                assertThat(IamResourceMatcher.anyCovers(resources, "easy-db-lab-test", key)).describedAs(key).isFalse()
            }
        }

        @Test
        fun `the compactor task role reaches the prefixed compacted roots and nothing else`() {
            val statements = statements(AWSPolicy.Inline.CompactorTaskAccess(prefix).toJson())
            val objects = statements.single { "s3:DeleteObject" in it.values("Action") }

            assertThat(objects.values("Resource")).containsExactlyInAnyOrder(
                "arn:aws:s3:::easy-db-lab-*/mimir/*",
                "arn:aws:s3:::easy-db-lab-*/loki/*",
                "arn:aws:s3:::easy-db-lab-*/tempo/*",
                "arn:aws:s3:::easy-db-lab-*/team-a/mimir/*",
                "arn:aws:s3:::easy-db-lab-*/team-a/loki/*",
                "arn:aws:s3:::easy-db-lab-*/team-a/tempo/*",
            )
            assertThat(statements.flatMap { it.values("Resource") }).noneMatch { it.contains("grafana") || it.contains("pyroscope") }
        }

        /** Whatever prefix passes validation, the compactor's allow stays off grafana/, reports/ and pyroscope/. */
        @Test
        fun `the compactor policy from any accepted prefix reaches nothing under the uncompacted roots`() {
            val accepted = listOf("", "team-a", "a/b", "grafana-x", "reports.v2", "pyroscope_old")
            // Keys a prefix could add under the uncompacted roots. `<root>/mimir/x` is left out: the
            // bucket wildcard `easy-db-lab-*` spans `/`, so `easy-db-lab-*/mimir/*` reaches it with
            // any prefix and without one, as it did before store prefixes existed.
            val forbidden =
                listOf("grafana", "reports", "pyroscope").flatMap { root ->
                    listOf("$root/x", "$root/annotations/acme/b.json", "$root/acme/lab-1/index.html")
                }

            accepted.forEach { storePrefix ->
                val resources =
                    statements(AWSPolicy.Inline.CompactorTaskAccess(storePrefix).toJson())
                        .single { "s3:DeleteObject" in it.values("Action") }
                        .values("Resource")
                val keys = forbidden + forbidden.map { "${storePrefix.trim('/')}/$it" }
                keys.forEach { key ->
                    assertThat(IamResourceMatcher.anyCovers(resources, "easy-db-lab-acct", key))
                        .describedAs("prefix '$storePrefix' reaches $key")
                        .isFalse()
                }
            }
        }

        @Test
        fun `the compactor policy refuses a prefix that could widen it`() {
            listOf("*", "..", "grafana", "pyroscope/x", "a/mimir").forEach { storePrefix ->
                assertThatThrownBy { AWSPolicy.Inline.CompactorTaskAccess(storePrefix).toJson() }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }

        @Test
        fun `slashes around the prefix are not doubled`() {
            assertThat(AWSPolicy.Inline.storeRoots("/team-a/", listOf("mimir"))).containsExactly("mimir", "team-a/mimir")
        }
    }

    @Test
    fun `the user policies include the compactor policy`() {
        val compactor = AWSPolicy.UserIAM.loadAll("123456789012").single { it.name == "EasyDBLabCompactor" }

        assertThat(compactor.body)
            .contains("arn:aws:iam::123456789012:role/EasyDBLabCompactorTaskRole")
            .contains("arn:aws:iam::123456789012:role/EasyDBLabCompactorExecutionRole")
            .doesNotContain("ACCOUNT_ID")
    }

    private fun statements(json: String): List<JsonObject> =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("Statement")
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.effect(): String = getValue("Effect").jsonPrimitive.content

    private fun JsonObject.values(key: String): List<String> =
        when (val element = getValue(key)) {
            is JsonArray -> element.map { it.jsonPrimitive.content }
            else -> listOf(element.jsonPrimitive.content)
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
