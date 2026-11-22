package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

/** A 5xx or 429 answer: the server may answer the same request on a later attempt. */
class TransientHttpStatusException(
    val url: String,
    val status: Int,
) : RuntimeException("GET $url answered HTTP $status")

/** An answer that a retry will not change (a 4xx other than 404 and 429). */
class HttpStatusException(
    val url: String,
    val status: Int,
) : RuntimeException("GET $url answered HTTP $status")

/**
 * GETs from the public version sources (the ASF archive, the GitHub API). The client goes direct,
 * never through the SOCKS tunnel: the tunnel reaches the cluster's private network, and these hosts
 * are on the internet. An IO error, a 5xx and a 429 are retried; a 404 is an answer, not a failure.
 */
class PublicHttp(
    private val client: OkHttpClient = defaultClient(),
    private val retryConfig: RetryConfig = retryConfig(),
) : ChecksumHttp {
    /**
     * The body at [url], or null when the server answers 404.
     *
     * @throws IOException when every attempt failed to connect or read
     * @throws TransientHttpStatusException when every attempt got a 5xx or 429
     * @throws HttpStatusException on any other non-success answer
     */
    override fun getOrNull(url: String): String? = withRetry { fetch(url) { body -> body.string() } }

    /**
     * [read] applied to the body at [url] as a stream, or null when the server answers 404: for a
     * download too large to hold as text. The same retries and failures as [getOrNull]; a retried
     * attempt reads from the start again.
     */
    override fun <T> streamOrNull(
        url: String,
        read: (InputStream) -> T,
    ): T? = withRetry { fetch(url) { body -> body.byteStream().use(read) } }

    private fun <T> withRetry(call: () -> T?): T? = Retry.decorateCheckedSupplier(Retry.of("public-http", retryConfig)) { call() }.get()

    private fun <T> fetch(
        url: String,
        read: (ResponseBody) -> T,
    ): T? {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> read(response.body)
                response.code == Constants.HttpStatus.NOT_FOUND -> null
                response.code == Constants.HttpStatus.TOO_MANY_REQUESTS || response.code >= Constants.HttpStatus.SERVER_ERROR_MIN ->
                    throw TransientHttpStatusException(url, response.code)
                else -> throw HttpStatusException(url, response.code)
            }
        }
    }

    companion object {
        /** GitHub refuses an API request without a User-Agent. */
        private const val USER_AGENT = "easy-db-lab"

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(Constants.Cassandra.SOURCE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(Constants.Cassandra.SOURCE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        /**
         * Retries an [IOException] (a refused or timed-out connection; OkHttp's is checked, so the call
         * is decorated with `decorateCheckedSupplier`) and a [TransientHttpStatusException] (a 5xx or
         * 429). Every other failure, a 404 or a parse error included, fails at once.
         *
         * Exponential backoff: 1s, 2s, 4s, from [backoffBase]; tests pass a short one.
         */
        fun retryConfig(backoffBase: Duration = Duration.ofMillis(Constants.Retry.EXPONENTIAL_BACKOFF_BASE_MS)): RetryConfig =
            RetryConfig
                .custom<Any>()
                .maxAttempts(Constants.Retry.MAX_NETWORK_RETRIES)
                .intervalFunction { attemptCount -> backoffBase.toMillis() * (1L shl (attemptCount - 1)) }
                .retryOnException { it is IOException || it is TransientHttpStatusException }
                .build()
    }
}
