package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * [PublicHttp.streamOrNull], the streamed GET `TarballDigests` hashes a tarball with when only a
 * sha512 is published: the same 404, retry and status rules as [PublicHttp.getOrNull].
 */
class PublicHttpStreamTest {
    private val codes = ArrayDeque<Int>()
    private var requests = 0

    private val http =
        PublicHttp(
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        requests++
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(codes.removeFirstOrNull() ?: Constants.HttpStatus.OK)
                            .message("")
                            .body("tarball bytes".toResponseBody("application/gzip".toMediaType()))
                            .build()
                    },
                ).build(),
            PublicHttp.retryConfig(Duration.ZERO),
        )

    private fun read(): String? = http.streamOrNull("https://h/t.tgz") { it.readBytes().decodeToString() }

    @Test
    fun `the body reaches the reader as a stream`() {
        assertThat(read()).isEqualTo("tarball bytes")
    }

    @Test
    fun `a 404 gives null and the reader never runs`() {
        codes += Constants.HttpStatus.NOT_FOUND
        var ran = false

        assertThat(http.streamOrNull("https://h/t.tgz") { ran = true }).isNull()
        assertThat(ran).isFalse()
    }

    @Test
    fun `a 5xx is retried and the next answer is read`() {
        codes += 503

        assertThat(read()).isEqualTo("tarball bytes")
        assertThat(requests).isEqualTo(2)
    }

    @Test
    fun `a 403 fails at once, naming the URL`() {
        codes += 403

        assertThatThrownBy { read() }.isInstanceOf(HttpStatusException::class.java).hasMessageContaining("https://h/t.tgz")
        assertThat(requests).isEqualTo(1)
    }
}
