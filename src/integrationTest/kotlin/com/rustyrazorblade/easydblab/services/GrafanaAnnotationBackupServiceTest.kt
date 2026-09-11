package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Integration tests for [DefaultGrafanaAnnotationBackupService].
 *
 * The service is driven against a [MockWebServer] standing in for Grafana's `GET /api/annotations`,
 * with a captured [ObjectStore] to assert what is uploaded and where. The tests pin the account-level
 * destination (outside any cluster prefix), the reported URI and count, and the fail-fast behavior
 * when no S3 bucket is configured.
 */
class GrafanaAnnotationBackupServiceTest : BaseKoinTest() {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var objectStore: ObjectStore
    private lateinit var service: DefaultGrafanaAnnotationBackupService

    private val controlHost =
        ClusterHost(
            publicIp = "127.0.0.1",
            privateIp = "10.0.1.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control",
        )

    private fun clusterState(bucket: String?) =
        ClusterState(
            name = "perf-test",
            versions = mutableMapOf(),
            s3Bucket = bucket,
            initConfig = InitConfig(region = "us-west-2"),
            hosts = mapOf(ServerType.Control to listOf(controlHost)),
        )

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        objectStore = mock()

        val interceptor =
            Interceptor { chain ->
                val original = chain.request()
                val redirected =
                    original.url
                        .newBuilder()
                        .host(mockWebServer.hostName)
                        .port(mockWebServer.port)
                        .build()
                chain.proceed(original.newBuilder().url(redirected).build())
            }
        val grafanaService =
            DefaultGrafanaDashboardService(
                k8sService = mock(),
                manifestBuilder = mock<GrafanaManifestBuilder>(),
                eventBus = getKoin().get<EventBus>(),
                okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            )
        service = DefaultGrafanaAnnotationBackupService(grafanaService, objectStore, getKoin().get<EventBus>())
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.close()
    }

    @Test
    fun `backup uploads annotations to the account-level location and reports the URI and count`() {
        val annotationsJson = """[{"id":1,"text":"a"},{"id":2,"text":"b"}]"""
        mockWebServer.enqueue(MockResponse(code = 200, body = annotationsJson))

        val result = service.backup(controlHost, clusterState("acct-bucket")).getOrThrow()

        val contentCaptor = argumentCaptor<String>()
        val pathCaptor = argumentCaptor<ClusterS3Path>()
        verify(objectStore).uploadContent(contentCaptor.capture(), pathCaptor.capture())

        // The exact JSON Grafana returned is uploaded verbatim.
        assertThat(contentCaptor.firstValue).isEqualTo(annotationsJson)
        // Account-level destination, not a per-cluster prefix that teardown expires.
        assertThat(pathCaptor.firstValue.getKey())
            .startsWith("grafana-annotations/perf-test/")
            .doesNotStartWith("clusters/")
        assertThat(result.annotationCount).isEqualTo(2)
        assertThat(result.s3Path.toUri()).startsWith("s3://acct-bucket/grafana-annotations/perf-test/")

        val request = mockWebServer.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/annotations")
        assertThat(request.method).isEqualTo("GET")
    }

    @Test
    fun `backup fails fast with the run-up-first message when no bucket is configured`() {
        val result = service.backup(controlHost, clusterState(null))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Run 'easy-db-lab up' first")
        verify(objectStore, never()).uploadContent(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }
}
