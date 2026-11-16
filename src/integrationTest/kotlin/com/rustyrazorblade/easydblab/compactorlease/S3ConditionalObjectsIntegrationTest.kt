package com.rustyrazorblade.easydblab.compactorlease

import com.rustyrazorblade.easydblab.SharedLocalStack
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * The conditional-write contract every adapter shares, against LocalStack's S3: the real
 * `If-None-Match: *` and `If-Match` requests, and S3's 412 and 404 answers. It runs here, not in
 * `:compactor-lease`, because LocalStack is the root module's shared integration container.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ConditionalObjectsIntegrationTest : ConditionalObjectsContract() {
    private companion object {
        const val BUCKET = "s3-conditional-objects-test-bucket"
    }

    private val s3 = SharedLocalStack.s3Client()

    @BeforeAll
    fun createBucket() {
        SharedLocalStack.createBucketIfMissing(s3, BUCKET)
    }

    override fun store(): ConditionalObjects = S3ConditionalObjects(s3, BUCKET)
}
