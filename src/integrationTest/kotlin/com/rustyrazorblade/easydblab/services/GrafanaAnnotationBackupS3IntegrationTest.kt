package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Two Grafana annotation backups of one cluster in the same second, written to a real S3 API
 * (LocalStack). Snapshot names have one-second resolution, so without a check the second backup
 * would write the first one's key and overwrite it. Both must survive, each under its own name.
 */
class GrafanaAnnotationBackupS3IntegrationTest : BaseKoinTest() {
    private val s3 = SharedLocalStack.s3Client()
    private val bucket = "annotation-backup-${UUID.randomUUID().toString().take(8)}"
    private val objectStore = S3ObjectStore(s3, EventBus())
    private val sameSecond = Clock.fixed(Instant.parse("2026-09-24T13:04:05Z"), ZoneOffset.UTC)
    private lateinit var grafana: MockWebServer
    private lateinit var service: DefaultGrafanaAnnotationBackupService

    private val controlHost =
        ClusterHost(publicIp = "127.0.0.1", privateIp = "10.0.1.1", alias = "control0", availabilityZone = "us-west-2a")

    private val clusterState =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = "c0ffee",
            s3Bucket = bucket,
            initConfig = InitConfig(region = "us-west-2", tenant = "acme"),
        )

    @BeforeEach
    fun setup() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        grafana = MockWebServer()
        grafana.start()
        val toGrafana =
            Interceptor { chain ->
                val original = chain.request()
                val redirected =
                    original.url
                        .newBuilder()
                        .host(grafana.hostName)
                        .port(grafana.port)
                        .build()
                chain.proceed(original.newBuilder().url(redirected).build())
            }
        val eventBus = getKoin().get<EventBus>()
        val dashboards =
            DefaultGrafanaDashboardService(
                k8sService = mock(),
                manifestBuilder = mock<GrafanaManifestBuilder>(),
                treeUploader = mock<GrafanaDashboardTreeUploader>(),
                eventBus = eventBus,
                okHttpClient = OkHttpClient.Builder().addInterceptor(toGrafana).build(),
                configChangeReport = ConfigChangeReport(mock(), eventBus),
            )
        service = DefaultGrafanaAnnotationBackupService(dashboards, objectStore, eventBus, sameSecond)
    }

    @AfterEach
    fun tearDown() {
        grafana.close()
    }

    @Test
    fun `a second backup in the same second never overwrites the first`() {
        val firstJson = """[{"id":1,"text":"first"}]"""
        val secondJson = """[{"id":1,"text":"first"},{"id":2,"text":"second"}]"""
        grafana.enqueue(MockResponse(code = 200, body = firstJson))
        grafana.enqueue(MockResponse(code = 200, body = secondJson))

        val first = service.backup(controlHost, clusterState).getOrThrow()
        val second = service.backup(controlHost, clusterState).getOrThrow()

        assertThat(second.s3Path).isNotEqualTo(first.s3Path)
        assertThat(first.s3Path.getKey()).isEqualTo("observability/annotations/acme/20260924-130405_lab-c0ffee.json")
        assertThat(second.s3Path.getKey()).isEqualTo("observability/annotations/acme/20260924-130406_lab-c0ffee.json")
        assertThat(objectStore.readContent(first.s3Path)).isEqualTo(firstJson)
        assertThat(objectStore.readContent(second.s3Path)).isEqualTo(secondJson)
    }
}
