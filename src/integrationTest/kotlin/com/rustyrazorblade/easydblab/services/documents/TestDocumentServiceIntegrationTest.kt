package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.s3.S3Client
import java.util.UUID

/**
 * [DefaultTestDocumentService] against S3 in LocalStack: the index of a test with no documents.
 *
 * Each test uses its own cluster id, so the tests share the bucket without seeing each other's
 * documents.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDocumentServiceIntegrationTest {
    private companion object {
        private const val BUCKET = "test-documents-bucket"
    }

    private lateinit var s3Client: S3Client
    private lateinit var objectStore: S3ObjectStore
    private lateinit var service: DefaultTestDocumentService

    @BeforeAll
    fun setup() {
        s3Client = SharedLocalStack.s3Client()
        SharedLocalStack.createBucketIfMissing(s3Client, BUCKET)
        objectStore = S3ObjectStore(s3Client, EventBus())
        service = DefaultTestDocumentService(objectStore)
    }

    private fun cluster(): ClusterState =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = UUID.randomUUID().toString(),
            s3Bucket = BUCKET,
            initConfig = InitConfig(tenant = "acme"),
        )

    private fun key(
        state: ClusterState,
        name: String,
    ): String = "reports/acme/${state.clusterLabelName()}/$name"

    private fun read(key: String): String = objectStore.readContent(ClusterS3Path.fromKey(BUCKET, key))

    @Test
    fun `a test with no documents gets an index that says so`() {
        val state = cluster()

        service.rebuildIndex(state)

        assertThat(read(key(state, "index.html"))).contains("No documents yet")
    }
}
