package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/**
 * Lists the tenants in a shared store on S3 (LocalStack) the way `up` and `grafana update-config`
 * do: one delimited listing of `mimir/`, whose common prefixes are the tenant directories. Objects
 * nested deep under a tenant and Mimir's own `__mimir_cluster/` must not leak into the result.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantDirectoryIntegrationTest {
    private companion object {
        const val BUCKET = "tenant-directory-it"
    }

    private val s3 = SharedLocalStack.s3Client()

    @BeforeAll
    fun seed() {
        SharedLocalStack.createBucketIfMissing(s3, BUCKET)
        listOf(
            "mimir/acme/01HBLOCK/meta.json",
            "mimir/acme/bucket-index.json.gz",
            "mimir/zeta/01HOTHER/chunks/000001",
            "mimir/__mimir_cluster/mimir_cluster_seed.json",
            "loki/other/chunk",
        ).forEach { key ->
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(key)
                    .build(),
                RequestBody.fromString("x"),
            )
        }
    }

    @Test
    fun `the tenant directories under mimir are the tenants, with the home tenant added`() {
        val tenants = TenantDirectory(S3ObjectStore(s3, EventBus())).list(BUCKET, home = "lab")

        assertThat(tenants).isEqualTo(TenantSet("lab", listOf("acme", "lab", "zeta")))
    }
}
