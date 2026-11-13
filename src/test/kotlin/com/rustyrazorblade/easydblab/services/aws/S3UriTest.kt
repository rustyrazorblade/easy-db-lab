package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class S3UriTest {
    @Test
    fun `uri renders bucket and key as an s3 URI`() {
        val path = ClusterStoragePath.root("my-bucket").resolve("spark/app.jar")

        assertThat(S3Uri.uri(path)).isEqualTo("s3://my-bucket/spark/app.jar")
    }

    @Test
    fun `uri of a bucket root has no trailing slash`() {
        assertThat(S3Uri.uri(ClusterStoragePath.root("my-bucket"))).isEqualTo("s3://my-bucket")
    }

    @Test
    fun `uri includes the store prefix in the key`() {
        val path = ClusterStoragePath.root("my-bucket", prefix = "team-a").resolve("reports")

        assertThat(S3Uri.uri(path)).isEqualTo("s3://my-bucket/team-a/reports")
    }

    @Test
    fun `parse reads bucket and key`() {
        val path = S3Uri.parse("s3://my-bucket/path/to/file.jar")

        assertThat(path.bucket).isEqualTo("my-bucket")
        assertThat(path.getKey()).isEqualTo("path/to/file.jar")
    }

    @Test
    fun `parse of a bucket-only URI gives the bucket root`() {
        val path = S3Uri.parse("s3://my-bucket")

        assertThat(path.bucket).isEqualTo("my-bucket")
        assertThat(path.getKey()).isEmpty()
    }

    @Test
    fun `parse round-trips with uri`() {
        val original = "s3://test-bucket/spark/jars/app-1.0.jar"

        assertThat(S3Uri.uri(S3Uri.parse(original))).isEqualTo(original)
    }

    @Test
    fun `parse rejects a URI with another scheme`() {
        assertThatThrownBy { S3Uri.parse("https://example.com/file.jar") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("s3://")
    }

    @Test
    fun `parse rejects a URI with no scheme`() {
        assertThatThrownBy { S3Uri.parse("my-bucket/file.jar") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("s3://")
    }

    @Test
    fun `parse rejects a URI with no bucket`() {
        assertThatThrownBy { S3Uri.parse("s3://") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("bucket")
    }
}
