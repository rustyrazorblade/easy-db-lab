package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CommonPrefix
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable

/**
 * The store check `local create` runs before it writes anything: the bucket's region, a
 * `HeadBucket` and a one-key list of `mimir/`. S3 is mocked at the SDK boundary; what is tested is
 * how each answer becomes readable or unreadable, which AWS error code the operator is shown, and
 * that everything after the region lookup goes to a client for the bucket's own region, not the
 * profile's (S3 answers a HEAD in another region with a 301).
 */
class SdkLocalStackStoreTest {
    /** The profile-region client: it only finds the bucket's region. */
    private val profileS3 = mock<S3Client>()

    /** The client for the bucket's region. */
    private val s3 = mock<S3Client>()
    private val requestedRegions = mutableListOf<String>()
    private val check =
        SdkLocalStackStore(
            profileS3,
            S3ClientForRegion { region ->
                requestedRegions += region
                s3
            },
            EventBus(),
        )

    private fun s3Error(
        code: String?,
        status: Int,
    ): S3Exception =
        S3Exception
            .builder()
            .statusCode(status)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
            .message("refused")
            .build() as S3Exception

    private fun readableBucket(location: String = "eu-west-1") {
        whenever(profileS3.getBucketLocation(any<GetBucketLocationRequest>()))
            .thenReturn(GetBucketLocationResponse.builder().locationConstraint(location).build())
        whenever(s3.headBucket(any<HeadBucketRequest>())).thenReturn(HeadBucketResponse.builder().build())
        whenever(s3.listObjectsV2(any<ListObjectsV2Request>())).thenReturn(ListObjectsV2Response.builder().build())
    }

    @Test
    fun `a readable bucket reports its region and lists only one key under mimir`() {
        readableBucket()

        val result = check.check("acct")

        assertThat(result).isEqualTo(StoreReadResult.Readable(region = "eu-west-1"))
        val list = argumentCaptor<ListObjectsV2Request>()
        verify(s3).listObjectsV2(list.capture())
        assertThat(list.firstValue.bucket()).isEqualTo("acct")
        assertThat(list.firstValue.prefix()).isEqualTo("mimir/")
        assertThat(list.firstValue.maxKeys()).isEqualTo(1)
    }

    @Test
    fun `the HEAD and the list go to a client for the bucket's region, never the profile's`() {
        readableBucket(location = "ap-south-1")

        check.check("acct")

        assertThat(requestedRegions).containsOnly("ap-south-1")
        verify(s3).headBucket(any<HeadBucketRequest>())
        verify(profileS3, never()).headBucket(any<HeadBucketRequest>())
        verify(profileS3, never()).listObjectsV2(any<ListObjectsV2Request>())
    }

    @Test
    fun `the tenants are listed with a client for the bucket's region`() {
        whenever(s3.listObjectsV2Paginator(any<ListObjectsV2Request>()))
            .thenAnswer { ListObjectsV2Iterable(s3, it.getArgument(0)) }
        whenever(s3.listObjectsV2(any<ListObjectsV2Request>())).thenReturn(
            ListObjectsV2Response
                .builder()
                .commonPrefixes(CommonPrefix.builder().prefix("mimir/acme/").build())
                .build(),
        )

        val tenants = check.tenants("acct", "ap-south-1", "default")

        assertThat(tenants.all).containsExactlyInAnyOrder("acme", "default")
        assertThat(requestedRegions).containsExactly("ap-south-1")
        verify(profileS3, never()).listObjectsV2Paginator(any<ListObjectsV2Request>())
    }

    @Test
    fun `the original region's empty location constraint is us-east-1`() {
        readableBucket(location = "")

        assertThat(check.check("acct")).isEqualTo(StoreReadResult.Readable(region = "us-east-1"))
    }

    @Test
    fun `an S3 refusal is unreadable with S3's error code`() {
        readableBucket()
        whenever(s3.listObjectsV2(any<ListObjectsV2Request>())).thenThrow(s3Error("AccessDenied", 403))

        assertThat(check.check("acct")).isEqualTo(StoreReadResult.Unreadable(awsErrorCode = "AccessDenied"))
    }

    @Test
    fun `a HEAD refusal carries no error code, so the HTTP status names it`() {
        readableBucket()
        whenever(s3.headBucket(any<HeadBucketRequest>())).thenThrow(s3Error(null, 403))

        assertThat(check.check("acct")).isEqualTo(StoreReadResult.Unreadable(awsErrorCode = "HTTP 403"))
        verify(s3, never()).listObjectsV2(any<ListObjectsV2Request>())
    }

    @Test
    fun `credentials the SDK cannot load are unreadable, named by the SDK's own reason`() {
        whenever(profileS3.getBucketLocation(any<GetBucketLocationRequest>()))
            .thenThrow(SdkClientException.create("The SSO session associated with this profile has expired"))

        val result = check.check("acct")

        assertThat(result).isEqualTo(
            StoreReadResult.Unreadable(awsErrorCode = "SdkClientException: The SSO session associated with this profile has expired"),
        )
    }
}
