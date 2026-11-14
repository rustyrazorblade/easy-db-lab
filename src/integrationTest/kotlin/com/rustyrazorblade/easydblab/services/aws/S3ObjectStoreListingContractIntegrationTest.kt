package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.ObjectStoreListingContract
import java.util.UUID

/**
 * The listing cases `InMemoryObjectStoreTest` runs, against [S3ObjectStore] and LocalStack S3, so
 * the in-memory store the unit tests use is held to what S3's prefix and delimiter listings return.
 * One class-unique bucket; each case lists under its own random directory.
 */
class S3ObjectStoreListingContractIntegrationTest : ObjectStoreListingContract() {
    private companion object {
        private const val BUCKET = "s3objectstore-listing-contract"
    }

    private val s3Client = SharedLocalStack.s3Client().also { SharedLocalStack.createBucketIfMissing(it, BUCKET) }

    override val store: ObjectStore = S3ObjectStore(s3Client, EventBus())

    override fun freshRoot(): ClusterStoragePath = ClusterStoragePath.root(BUCKET).resolve("case-${UUID.randomUUID()}")
}
