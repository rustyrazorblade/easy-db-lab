package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Result of a streaming operation.
 *
 * @property bytesTransferred Number of bytes transferred from source to target
 */
data class StreamResult(
    val bytesTransferred: Long,
)

/**
 * Service for streaming data from a running Victoria instance on the cluster
 * to an external Victoria instance via HTTP API.
 *
 * Metrics use VictoriaMetrics native export/import format.
 * Logs use VictoriaLogs NDJSON export and jsonline import.
 */
interface VictoriaStreamService {
    /**
     * Stream metrics from the cluster's VictoriaMetrics to an external instance.
     *
     * @param controlHost The control node running VictoriaMetrics
     * @param targetUrl Base URL of the target VictoriaMetrics (e.g., "http://victoria:8428")
     * @param match Metric selector for filtering (default: all metrics)
     * @return Result containing StreamResult on success
     */
    fun streamMetrics(
        controlHost: ClusterHost,
        targetUrl: String,
        match: String = Constants.Victoria.DEFAULT_METRICS_MATCH,
    ): Result<StreamResult>

    /**
     * Stream logs from the cluster's VictoriaLogs to an external instance.
     *
     * @param controlHost The control node running VictoriaLogs
     * @param targetUrl Base URL of the target VictoriaLogs (e.g., "http://victorialogs:9428")
     * @param query LogsQL query for filtering (default: all logs)
     * @return Result containing StreamResult on success
     */
    fun streamLogs(
        controlHost: ClusterHost,
        targetUrl: String,
        query: String = Constants.Victoria.DEFAULT_LOGS_QUERY,
    ): Result<StreamResult>
}

/**
 * Default implementation that streams data via HTTP using SOCKS proxy for source
 * and direct connection for target.
 */
class DefaultVictoriaStreamService(
    private val socksProxyService: SocksProxyService,
    private val httpClientFactory: HttpClientFactory,
) : VictoriaStreamService {
    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val STREAM_TIMEOUT_MINUTES = 30L
        private const val BUFFER_SIZE = 8192
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val NDJSON = "application/x-ndjson".toMediaType()
    }

    private val directClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(STREAM_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .readTimeout(STREAM_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(STREAM_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    override fun streamMetrics(
        controlHost: ClusterHost,
        targetUrl: String,
        match: String,
    ): Result<StreamResult> =
        runCatching {
            socksProxyService.ensureRunning(controlHost)
            val proxiedClient = httpClientFactory.createClient()

            val encodedMatch = URLEncoder.encode(match, StandardCharsets.UTF_8)
            val sourceUrl =
                "http://${controlHost.privateIp}:${Constants.K8s.VICTORIAMETRICS_PORT}" +
                    "${Constants.Victoria.METRICS_EXPORT_PATH}?match[]=$encodedMatch"
            val targetImportUrl = "${targetUrl.trimEnd('/')}${Constants.Victoria.METRICS_IMPORT_PATH}"

            log.info { "Streaming metrics from $sourceUrl to $targetImportUrl" }

            streamData(proxiedClient, sourceUrl, targetImportUrl, OCTET_STREAM)
        }

    override fun streamLogs(
        controlHost: ClusterHost,
        targetUrl: String,
        query: String,
    ): Result<StreamResult> =
        runCatching {
            socksProxyService.ensureRunning(controlHost)
            val proxiedClient = httpClientFactory.createClient()

            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val sourceUrl =
                "http://${controlHost.privateIp}:${Constants.K8s.VICTORIALOGS_PORT}" +
                    "${Constants.Victoria.LOGS_EXPORT_PATH}?query=$encodedQuery"
            val targetImportUrl = "${targetUrl.trimEnd('/')}${Constants.Victoria.LOGS_IMPORT_PATH}"

            log.info { "Streaming logs from $sourceUrl to $targetImportUrl" }

            streamData(proxiedClient, sourceUrl, targetImportUrl, NDJSON)
        }

    private fun streamData(
        sourceClient: OkHttpClient,
        sourceUrl: String,
        targetUrl: String,
        contentType: okhttp3.MediaType,
    ): StreamResult {
        val sourceRequest =
            Request
                .Builder()
                .url(sourceUrl)
                .get()
                .build()

        val sourceResponse = sourceClient.newCall(sourceRequest).execute()

        if (sourceResponse.code != HTTP_OK) {
            val errorBody = sourceResponse.body.string()
            sourceResponse.close()
            error("Source returned HTTP ${sourceResponse.code}: $errorBody")
        }

        val sourceBody = sourceResponse.body
        var bytesTransferred = 0L

        val streamingBody =
            object : RequestBody() {
                override fun contentType() = contentType

                override fun writeTo(sink: BufferedSink) {
                    sourceBody.source().use { source ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val bytesRead = source.read(buffer)
                            if (bytesRead == -1) break
                            sink.write(buffer, 0, bytesRead)
                            bytesTransferred += bytesRead
                        }
                    }
                }
            }

        val targetRequest =
            Request
                .Builder()
                .url(targetUrl)
                .post(streamingBody)
                .build()

        directClient.newCall(targetRequest).execute().use { targetResponse ->
            if (targetResponse.code != HTTP_OK && targetResponse.code != HTTP_NO_CONTENT) {
                val errorBody = targetResponse.body.string()
                error("Target returned HTTP ${targetResponse.code}: $errorBody")
            }
        }

        log.info { "Streaming complete. Transferred $bytesTransferred bytes." }
        return StreamResult(bytesTransferred)
    }
}
