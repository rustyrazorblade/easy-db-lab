package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Runs Mimir and Loki at the images the cluster deploys, with the configuration the cluster renders,
 * against the shared LocalStack.
 *
 * The one edit to a rendered config is the harness's: the S3 endpoint and credentials point at
 * LocalStack instead of the regional endpoint and the instance role. Every other setting is what a
 * cluster runs. The data directory is a Docker volume standing in for the control node's hostPath,
 * so a test can kill a backend and start another on the same data. Call
 * `Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())` before starting either.
 */
object ObservabilityBackends {
    private const val MIMIR_DATA = "/data"
    private const val LOKI_DATA = "/loki"
    private val STARTUP: Duration = Duration.ofMinutes(3)
    private val http: HttpClient = HttpClient.newHttpClient()

    /** Mimir's rendered config, pointed at LocalStack. */
    fun mimirConfig(rendered: String): String {
        val endpointLine = "    endpoint: s3.\${AWS_REGION}.amazonaws.com"
        check(rendered.contains(endpointLine)) { "rendered mimir.yaml no longer names the regional S3 endpoint" }
        return rendered.replace(
            endpointLine,
            """
            |    endpoint: host.testcontainers.internal:${SharedLocalStack.hostPort()}
            |    insecure: true
            |    bucket_lookup_type: path
            |    access_key_id: ${SharedLocalStack.accessKey()}
            |    secret_access_key: ${SharedLocalStack.secretKey()}
            """.trimMargin(),
        )
    }

    /** Loki's rendered config, pointed at LocalStack. */
    fun lokiConfig(rendered: String): String {
        val endpointLine = "      endpoint: s3.\${AWS_REGION}.amazonaws.com"
        check(rendered.contains(endpointLine)) { "rendered loki.yaml no longer names the regional S3 endpoint" }
        return rendered.replace(
            endpointLine,
            """
            |      endpoint: http://host.testcontainers.internal:${SharedLocalStack.hostPort()}
            |      insecure: true
            |      s3forcepathstyle: true
            |      access_key_id: ${SharedLocalStack.accessKey()}
            |      secret_access_key: ${SharedLocalStack.secretKey()}
            """.trimMargin(),
        )
    }

    /** Starts Mimir with [config] on the Docker volume [volume], writing to [bucket]. */
    fun startMimir(
        config: String,
        volume: String,
        bucket: String,
        metricsPrefix: String,
    ): GenericContainer<*> =
        GenericContainer(MimirManifestBuilder.IMAGE)
            .withCreateContainerCmdModifier { cmd -> cmd.hostConfig?.withBinds(Bind(volume, Volume(MIMIR_DATA))) }
            .withCopyToContainer(Transferable.of(config), "/etc/mimir/mimir.yaml")
            .withEnv("S3_BUCKET", bucket)
            .withEnv("AWS_REGION", SharedLocalStack.region())
            .withEnv("METRICS_S3_PREFIX", metricsPrefix)
            .withCommand("-config.file=/etc/mimir/mimir.yaml", "-config.expand-env=true")
            .withExposedPorts(Constants.K8s.MIMIR_HTTP_PORT)
            .waitingFor(Wait.forHttp("/ready").forPort(Constants.K8s.MIMIR_HTTP_PORT).withStartupTimeout(STARTUP))
            .apply { start() }

    /**
     * Starts Loki with [config] on the Docker volume [volume], writing to [bucket] as the ingester
     * `<tenant>.<clusterName>`. Runs as root so a fresh volume needs no chown; the pod runs as
     * [LokiManifestBuilder.LOKI_UID] on a directory `up` chowns.
     */
    fun startLoki(
        config: String,
        volume: String,
        bucket: String,
        logsPrefix: String,
        tenant: String,
        clusterName: String,
    ): GenericContainer<*> =
        GenericContainer(LokiManifestBuilder.IMAGE)
            .withCreateContainerCmdModifier { cmd ->
                cmd.withUser("root")
                cmd.hostConfig?.withBinds(Bind(volume, Volume(LOKI_DATA)))
            }.withCopyToContainer(Transferable.of(config), "/etc/loki/loki.yaml")
            .withEnv("S3_BUCKET", bucket)
            .withEnv("AWS_REGION", SharedLocalStack.region())
            .withEnv("LOGS_S3_PREFIX", logsPrefix)
            .withEnv("TENANT", tenant)
            .withEnv("CLUSTER_NAME", clusterName)
            .withCommand("-config.file=/etc/loki/loki.yaml", "-config.expand-env=true")
            .withExposedPorts(Constants.K8s.LOKI_HTTP_PORT)
            .waitingFor(Wait.forHttp("/ready").forPort(Constants.K8s.LOKI_HTTP_PORT).withStartupTimeout(STARTUP))
            .apply { start() }

    /** The base URL of [container]'s HTTP port [port] on the Docker host. */
    fun baseUrl(
        container: GenericContainer<*>,
        port: Int,
    ): String = "http://${container.host}:${container.getMappedPort(port)}"

    /** Sends [request] and returns the response, body as text. */
    fun send(request: HttpRequest): HttpResponse<String> = http.send(request, HttpResponse.BodyHandlers.ofString())

    /** A GET of [url] carrying [tenant] in `X-Scope-OrgID`. */
    fun get(
        url: String,
        tenant: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(URI(url))
                .header(Constants.Observability.TENANT_HEADER, tenant)
                .GET()
                .build(),
        )
}

/**
 * [ObservabilityHttp] for tests: sends each request to the container serving that port, with the
 * tenant header, instead of to a control node through the tunnel.
 *
 * @property baseUrl the base URL of the container serving a backend port, looked up per request
 *   because a test may replace the container.
 */
class ContainerObservabilityHttp(
    private val tenant: String,
    private val baseUrl: (Int) -> String,
) : ObservabilityHttp {
    private val http: HttpClient = HttpClient.newHttpClient()

    override fun get(
        port: Int,
        pathAndQuery: String,
        timeout: Duration,
    ): ObservabilityResponse = send(port, pathAndQuery, timeout) { it.GET() }

    override fun post(
        port: Int,
        path: String,
        body: String,
        contentType: String,
        timeout: Duration,
    ): ObservabilityResponse =
        send(port, path, timeout) { it.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)) }

    private fun send(
        port: Int,
        path: String,
        timeout: Duration,
        method: (HttpRequest.Builder) -> HttpRequest.Builder,
    ): ObservabilityResponse {
        val request =
            method(HttpRequest.newBuilder(URI("${baseUrl(port)}$path")).timeout(timeout))
                .header(Constants.Observability.TENANT_HEADER, tenant)
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return ObservabilityResponse(response.statusCode(), response.body())
    }
}
