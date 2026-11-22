package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RegionalS3Presigners
import com.rustyrazorblade.easydblab.services.ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse
import java.net.URI
import java.time.Duration

/**
 * The [ObjectStore] the application resolves from [awsServicesModule] can presign. A store built
 * without the presigner fails only when a catalog build is started on the cassandra-pods kit, so
 * the wiring itself is checked here, not a store the test builds by hand.
 */
internal class ObjectStoreBindingTest {
    @Test
    fun `the ObjectStore bound by the AWS services module presigns a GET`() {
        val s3 =
            mock<S3Client>().also {
                whenever(it.getBucketLocation(any<GetBucketLocationRequest>()))
                    .thenReturn(GetBucketLocationResponse.builder().locationConstraint("us-west-2").build())
            }
        val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret"))
        val app =
            koinApplication {
                modules(
                    awsServicesModule,
                    module {
                        single { s3 }
                        single { EventBus() }
                        single { RegionalS3Presigners(credentials) }
                    },
                )
            }
        try {
            val path = ClusterStoragePath.cassandraBuildsRoot("acct").resolve("b").resolve("b-bin.tar.gz")

            val url = URI(app.koin.get<ObjectStore>().presignedGetUrl(path, Duration.ofHours(1)))

            assertThat(url.path).endsWith("/${path.getKey()}")
            assertThat(url.query).contains("X-Amz-Signature=")
        } finally {
            app.close()
        }
    }
}
