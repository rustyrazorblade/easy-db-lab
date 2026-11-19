package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.ContainerNetwork
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Runs Mimir, Loki, Tempo and Pyroscope at the images the cluster deploys, with the configuration the cluster renders,
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
    private const val TEMPO_DATA = "/var/tempo"
    private const val PYROSCOPE_DATA = "/data"
    private const val HTTP_OK = 200

    /** How long the Pyroscope Deployment's startup probe lets the server take to answer `/ready`. */
    private val PYROSCOPE_STARTUP_PROBE_BUDGET: Duration =
        Duration.ofSeconds(
            Constants.PyroscopeProbes.STARTUP_PERIOD_SECONDS.toLong() *
                Constants.PyroscopeProbes.STARTUP_FAILURE_THRESHOLD,
        )

    /** Tempo's OTLP HTTP receiver port. */
    const val TEMPO_OTLP_HTTP_PORT = 4321
    private val STARTUP: Duration = Duration.ofMinutes(3)
    private val READY_POLL: Duration = Duration.ofMillis(500)
    private val GRACE: Duration = Duration.ofSeconds(30)
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

    // Longer than any query a test runs on a cold store, so only a backend that hangs reaches it.
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

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

    /** Tempo's rendered config, pointed at LocalStack. */
    fun tempoConfig(rendered: String): String {
        val endpointLine = "      endpoint: s3.\${AWS_REGION}.amazonaws.com"
        check(rendered.contains(endpointLine)) { "rendered tempo.yaml no longer names the regional S3 endpoint" }
        return rendered.replace(
            endpointLine,
            """
            |      endpoint: host.testcontainers.internal:${SharedLocalStack.hostPort()}
            |      insecure: true
            |      forcepathstyle: true
            |      access_key: ${SharedLocalStack.accessKey()}
            |      secret_key: ${SharedLocalStack.secretKey()}
            """.trimMargin(),
        )
    }

    /** Pyroscope's rendered config.yaml, pointed at LocalStack. */
    fun pyroscopeConfig(rendered: String): String {
        val endpointLine = "    endpoint: s3.${SharedLocalStack.region()}.amazonaws.com"
        check(rendered.contains(endpointLine)) { "rendered config no longer names the regional S3 endpoint" }
        return rendered.replace(
            endpointLine,
            """
            |    endpoint: host.testcontainers.internal:${SharedLocalStack.hostPort()}
            |    insecure: true
            |    force_path_style: true
            |    access_key_id: ${SharedLocalStack.accessKey()}
            |    secret_access_key: ${SharedLocalStack.secretKey()}
            """.trimMargin(),
        )
    }

    /**
     * Starts the Pyroscope server with [config] on the Docker volume [volume] (its v2 metastore,
     * the control node's `/mnt/db1/pyroscope`). Runs as root so a fresh volume needs no chown.
     *
     * It must answer `/ready` within the Deployment's startup-probe budget, the window the kubelet
     * allows before it kills the server, so every start also checks that budget.
     */
    fun startPyroscope(
        config: String,
        volume: String,
    ): GenericContainer<*> =
        pyroscopeContainer(config, volume)
            .waitingFor(
                Wait
                    .forHttp("/ready")
                    .forPort(PyroscopeManifestBuilder.SERVER_PORT)
                    .forStatusCode(HTTP_OK)
                    .withStartupTimeout(PYROSCOPE_STARTUP_PROBE_BUDGET),
            ).apply { start() }

    /**
     * Starts the Pyroscope server like [startPyroscope], but returns as soon as its HTTP port is
     * open, without waiting for `/ready`. A Java agent with queued profiles pushes at that moment,
     * so a test of what a restarted server does with those pushes starts it this way, then calls
     * [awaitPyroscopeReady].
     */
    fun startPyroscopeListening(
        config: String,
        volume: String,
    ): GenericContainer<*> =
        pyroscopeContainer(config, volume)
            .waitingFor(
                // Any HTTP answer means the port is open. forListeningPort execs /bin/sh in the
                // container to check, and the Pyroscope image has no shell.
                Wait
                    .forHttp("/ready")
                    .forPort(PyroscopeManifestBuilder.SERVER_PORT)
                    .forStatusCodeMatching { true }
                    .withStartupTimeout(PYROSCOPE_STARTUP_PROBE_BUDGET),
            ).apply { start() }

    /**
     * Waits until [pyroscope] answers `/ready`, and fails if that takes longer than the
     * Deployment's startup-probe budget counted from [startedAt] (a [System.nanoTime]), the window
     * the kubelet allows before it kills the server.
     */
    fun awaitPyroscopeReady(
        pyroscope: GenericContainer<*>,
        startedAt: Long,
    ) {
        val deadline = startedAt + PYROSCOPE_STARTUP_PROBE_BUDGET.toNanos()
        val url = "${baseUrl(pyroscope, PyroscopeManifestBuilder.SERVER_PORT)}/ready"
        while (System.nanoTime() < deadline) {
            val ready = runCatching { send(HttpRequest.newBuilder(URI(url)).GET().build()).statusCode() == HTTP_OK }
            if (ready.getOrDefault(false)) return
            Thread.sleep(READY_POLL.toMillis())
        }
        error("Pyroscope did not answer /ready within the startup-probe budget of $PYROSCOPE_STARTUP_PROBE_BUDGET")
    }

    private fun pyroscopeContainer(
        config: String,
        volume: String,
    ): GenericContainer<*> =
        GenericContainer(PyroscopeManifestBuilder.SERVER_IMAGE)
            .withCreateContainerCmdModifier { cmd ->
                cmd.withUser("root")
                cmd.hostConfig?.withBinds(Bind(volume, Volume(PYROSCOPE_DATA)))
            }.withCopyToContainer(Transferable.of(config), "/etc/pyroscope/config.yaml")
            .withCommand("-config.file=/etc/pyroscope/config.yaml")
            .withExposedPorts(PyroscopeManifestBuilder.SERVER_PORT)

    /**
     * Starts Tempo with [config] on the Docker volume [volume] (its WAL, the control node's
     * `/mnt/db1/tempo`), writing to [bucket]. Runs as root so a fresh volume needs no chown.
     */
    fun startTempo(
        config: String,
        volume: String,
        bucket: String,
        tracesPrefix: String,
        clusterName: String,
    ): GenericContainer<*> =
        GenericContainer(TempoManifestBuilder.IMAGE)
            .withCreateContainerCmdModifier { cmd ->
                cmd.withUser("root")
                cmd.hostConfig?.withBinds(Bind(volume, Volume(TEMPO_DATA)))
            }.withCopyToContainer(Transferable.of(config), "/etc/tempo/tempo.yaml")
            .withEnv("S3_BUCKET", bucket)
            .withEnv("AWS_REGION", SharedLocalStack.region())
            .withEnv("TRACES_S3_PREFIX", tracesPrefix)
            .withEnv("CLUSTER_NAME", clusterName)
            .withCommand("-config.file=/etc/tempo/tempo.yaml", "-config.expand-env=true")
            .withExposedPorts(Constants.K8s.TEMPO_PORT, TEMPO_OTLP_HTTP_PORT)
            .waitingFor(Wait.forHttp("/ready").forPort(Constants.K8s.TEMPO_PORT).withStartupTimeout(STARTUP))
            .apply { start() }

    /**
     * Starts Mimir with [config] on the Docker volume [volume], writing to [bucket], with the memcached
     * sidecar the cluster runs beside it ([MimirWithMemcached]).
     */
    fun startMimir(
        config: String,
        volume: String,
        bucket: String,
        metricsPrefix: String,
    ): GenericContainer<*> =
        MimirWithMemcached()
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
     * Starts Loki with [config] on the Docker volume [volume], writing to [bucket] as [ingester].
     * Runs as root so a fresh volume needs no chown; the pod runs as [LokiManifestBuilder.LOKI_UID]
     * on a directory `up` chowns.
     */
    fun startLoki(
        config: String,
        volume: String,
        bucket: String,
        logsPrefix: String,
        ingester: LokiIngester,
    ): GenericContainer<*> =
        GenericContainer(LokiManifestBuilder.IMAGE)
            .withCreateContainerCmdModifier { cmd ->
                cmd.withUser("root")
                cmd.hostConfig?.withBinds(Bind(volume, Volume(LOKI_DATA)))
            }.withCopyToContainer(Transferable.of(config), "/etc/loki/loki.yaml")
            .withEnv("S3_BUCKET", bucket)
            .withEnv("AWS_REGION", SharedLocalStack.region())
            .withEnv("LOGS_S3_PREFIX", logsPrefix)
            .withEnv("TENANT", ingester.tenant)
            .withEnv("CLUSTER_NAME", ingester.clusterName)
            .withCommand("-config.file=/etc/loki/loki.yaml", "-config.expand-env=true")
            .withExposedPorts(Constants.K8s.LOKI_HTTP_PORT)
            .waitingFor(Wait.forHttp("/ready").forPort(Constants.K8s.LOKI_HTTP_PORT).withStartupTimeout(STARTUP))
            .apply { start() }

    /**
     * Puts the started, ready [container] behind the in-cluster name [alias] on the Docker network
     * [networkId], as a Kubernetes Service does: the name resolves to the backend only once its
     * readiness wait has passed, and stops resolving when the container is gone, so a client sees a
     * connection error, never a backend that is still starting.
     */
    fun attachAsService(
        container: GenericContainer<*>,
        networkId: String,
        alias: String,
    ) {
        DockerClientFactory
            .instance()
            .client()
            .connectToNetworkCmd()
            .withContainerId(container.containerId)
            .withNetworkId(networkId)
            .withContainerNetwork(ContainerNetwork().withAliases(alias))
            .exec()
    }

    /**
     * Stops [container] the way a pod restart does: SIGTERM and a grace period to flush, then it is
     * removed. Its Service name keeps resolving until the process exits. Kubernetes removes a
     * terminating pod's endpoint at the same time as it signals the pod, not before, so a client can
     * still reach a backend that is shutting down; this is the harsher end of that race.
     */
    fun stopGracefully(container: GenericContainer<*>) {
        DockerClientFactory
            .instance()
            .client()
            .stopContainerCmd(container.containerId)
            .withTimeout(GRACE.toSeconds().toInt())
            .exec()
        container.stop()
    }

    /** The base URL of [container]'s HTTP port [port] on the Docker host. */
    fun baseUrl(
        container: GenericContainer<*>,
        port: Int,
    ): String = "http://${container.host}:${container.getMappedPort(port)}"

    /**
     * Sends [request] and returns the response, body as text. A request without its own timeout
     * gets [REQUEST_TIMEOUT], so a backend that accepts the connection and then hangs while it
     * restarts cannot hold a poll loop past its deadline or stall the producer.
     */
    fun send(request: HttpRequest): HttpResponse<String> {
        val bounded =
            if (request.timeout().isPresent) {
                request
            } else {
                HttpRequest.newBuilder(request) { _, _ -> true }.timeout(REQUEST_TIMEOUT).build()
            }
        return http.send(bounded, HttpResponse.BodyHandlers.ofString())
    }

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
 * Who a Loki test server ingests as: the cluster's ingester id is `<tenant>.<clusterName>`, so the
 * two travel together.
 */
data class LokiIngester(
    val tenant: String,
    val clusterName: String,
)

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

/**
 * Mimir with the memcached sidecar the cluster runs in its pod. On the control node both share the
 * host network, and `mimir.yaml` addresses the caches at `127.0.0.1:11211`. Here memcached joins
 * Mimir's network namespace as Mimir starts, before its readiness wait, so the rendered address
 * reaches it unchanged. It is created with the Docker client, as CompactorTaskIntegrationTest does:
 * Testcontainers would attach it to its host-access network, which Docker refuses for a joined
 * namespace. It carries the session labels, so Ryuk removes it too. Stopping Mimir removes it.
 */
private class MimirWithMemcached : GenericContainer<MimirWithMemcached>(MimirManifestBuilder.IMAGE) {
    private val docker = DockerClientFactory.instance().client()
    private val memcachedIds = mutableListOf<String>()

    override fun containerIsStarting(containerInfo: InspectContainerResponse) {
        RemoteDockerImage(DockerImageName.parse(Constants.K8s.MIMIR_MEMCACHED_IMAGE)).get()
        val id =
            docker
                .createContainerCmd(Constants.K8s.MIMIR_MEMCACHED_IMAGE)
                .withCmd(MimirManifestBuilder.MEMCACHED_ARGS)
                .withLabels(
                    DockerClientFactory.DEFAULT_LABELS +
                        (DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL to DockerClientFactory.SESSION_ID),
                ).withHostConfig(HostConfig.newHostConfig().withNetworkMode("container:${containerInfo.id}"))
                .exec()
                .id
        memcachedIds += id
        docker.startContainerCmd(id).exec()
    }

    override fun stop() {
        memcachedIds.forEach { id -> runCatching { docker.removeContainerCmd(id).withForce(true).exec() } }
        memcachedIds.clear()
        super.stop()
    }
}
