package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.ContainerNetwork
import com.github.dockerjava.api.model.DockerObjectAccessor
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    private const val PYROSCOPE_CONFIG = "/etc/pyroscope/config.yaml"
    private const val PYROSCOPE_ARGS = "-config.file=$PYROSCOPE_CONFIG"

    /** Where a gated Pyroscope's gate volume is mounted, in the server and in whoever releases it. */
    const val PYROSCOPE_GATE = "/gate"

    /** The static busybox a test runs in another container's namespace, or copies into a shell-less image. */
    const val BUSYBOX_IMAGE = "busybox:1.37.0-musl"
    private const val GATE_RELEASE = "release"
    private const val GATE_BUSYBOX = "$PYROSCOPE_GATE/busybox"
    private const val GATE_POLL_MICROS = 1000

    /** The labels that put a container made with the Docker client into this session, so Ryuk removes it. */
    val SESSION_LABELS: Map<String, String> =
        DockerClientFactory.DEFAULT_LABELS + (DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL to DockerClientFactory.SESSION_ID)
    private const val HTTP_OK = 200

    /** The Docker endpoint setting that picks which network gives a container its default route (API 1.48+). */
    private const val GATEWAY_PRIORITY = "GwPriority"
    private const val LOWEST_GATEWAY_PRIORITY = -1

    /**
     * The engine API that reads [GATEWAY_PRIORITY]. The engine reads it on a request at any API
     * version, Testcontainers' default 1.44 included, so only the engine's own version matters.
     */
    private const val GATEWAY_PRIORITY_API = "1.48"

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
     * A Pyroscope server started by [startPyroscopeGated], held at its start gate until a process
     * creates [release] in the gate volume, which it mounts at [PYROSCOPE_GATE]. [startedAt] is when
     * its container started, a [System.nanoTime] value, where the kubelet starts counting the
     * startup-probe budget.
     */
    data class GatedPyroscope(
        val container: GenericContainer<*>,
        val startedAt: Long,
    ) {
        val release: String = "$PYROSCOPE_GATE/$GATE_RELEASE"
    }

    /**
     * Starts the Pyroscope server like [startPyroscope], but held before its binary runs, and
     * returns as soon as its container runs. A Java agent with queued profiles pushes the moment
     * the port opens, so a test of what a restarted server does with those pushes starts it this
     * way. The test then starts a pusher in the server's network namespace, with [gateVolume]
     * mounted at [PYROSCOPE_GATE]. The pusher sees the port closed, releases the gate and pushes,
     * so no moment of the start can pass before it watches. Docker will not start a container in
     * the namespace of one that is not running yet, so the gate stands in for that order.
     * Afterwards the test calls [awaitPyroscopeReady] with [GatedPyroscope.startedAt].
     *
     * The image has no shell, so the gate is a static busybox copied into [gateVolume]. It waits
     * for the release file, then execs Pyroscope with its usual arguments.
     */
    fun startPyroscopeGated(
        config: String,
        volume: String,
        gateVolume: String,
        onCreated: (GenericContainer<*>) -> Unit = {},
    ): GatedPyroscope {
        installGate(gateVolume)
        val container = pyroscopeContainer(config, volume, gateVolume).waitingFor(ContainerStarted)
        onCreated(container)
        container.start()
        return GatedPyroscope(container, System.nanoTime())
    }

    /** Copies the static busybox into [gateVolume], for a server image that has no shell. */
    private fun installGate(gateVolume: String) {
        val docker = DockerClientFactory.instance().client()
        RemoteDockerImage(DockerImageName.parse(BUSYBOX_IMAGE)).get()
        val id =
            docker
                .createContainerCmd(BUSYBOX_IMAGE)
                .withCmd("/bin/busybox", "cp", "/bin/busybox", GATE_BUSYBOX)
                .withLabels(SESSION_LABELS)
                .withHostConfig(HostConfig.newHostConfig().withBinds(Bind(gateVolume, Volume(PYROSCOPE_GATE))))
                .exec()
                .id
        try {
            docker.startContainerCmd(id).exec()
            val exit = docker.waitContainerCmd(id).start().awaitStatusCode()
            check(exit == 0) { "copying the start gate's busybox exited $exit" }
        } finally {
            docker.removeContainerCmd(id).withForce(true).exec()
        }
    }

    /**
     * Creates a container of [image] in the network namespace of the running container [joinedId],
     * copies [fileContent] into it at [filePath], starts it, and returns its id. [configure] sets the
     * command, environment and any binds (through `hostConfig`). The container is made with the
     * Docker client, because Docker refuses a joined namespace for a container that carries the
     * host-access name Testcontainers adds; it carries [SESSION_LABELS], so Ryuk removes it. If the
     * copy or the start fails, the container is removed before the error is thrown.
     */
    fun startInNamespaceOf(
        joinedId: String,
        image: String,
        filePath: String,
        fileContent: String,
        configure: CreateContainerCmd.() -> Unit,
    ): String {
        val docker = DockerClientFactory.instance().client()
        RemoteDockerImage(DockerImageName.parse(image)).get()
        val id =
            docker
                .createContainerCmd(image)
                .withLabels(SESSION_LABELS)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("container:$joinedId"))
                .apply(configure)
                .exec()
                .id
        runCatching {
            val tar = ByteArrayOutputStream()
            TarArchiveOutputStream(tar).use { Transferable.of(fileContent).transferTo(it, filePath.removePrefix("/")) }
            docker
                .copyArchiveToContainerCmd(id)
                .withTarInputStream(ByteArrayInputStream(tar.toByteArray()))
                .withRemotePath("/")
                .exec()
            docker.startContainerCmd(id).exec()
        }.onFailure {
            runCatching { docker.removeContainerCmd(id).withForce(true).exec() }
            throw it
        }
        return id
    }

    /** A wait strategy that returns as soon as the container is running, for a caller that polls itself. */
    private object ContainerStarted : AbstractWaitStrategy() {
        override fun waitUntilReady() = Unit
    }

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

    /** The server on [volume]; with a [gateVolume], held at the start gate [startPyroscopeGated] describes. */
    private fun pyroscopeContainer(
        config: String,
        volume: String,
        gateVolume: String? = null,
    ): GenericContainer<*> =
        GenericContainer(PyroscopeManifestBuilder.SERVER_IMAGE)
            .withCreateContainerCmdModifier { cmd ->
                cmd.withUser("root")
                val data = Bind(volume, Volume(PYROSCOPE_DATA))
                if (gateVolume == null) {
                    cmd.hostConfig?.withBinds(data)
                } else {
                    cmd.hostConfig?.withBinds(data, Bind(gateVolume, Volume(PYROSCOPE_GATE)))
                    cmd.withEntrypoint(GATE_BUSYBOX)
                    cmd.withCmd(
                        "sh",
                        "-c",
                        "until [ -e $PYROSCOPE_GATE/$GATE_RELEASE ]; do $GATE_BUSYBOX usleep $GATE_POLL_MICROS; done; " +
                            "exec /usr/bin/pyroscope $PYROSCOPE_ARGS",
                    )
                }
            }.withCopyToContainer(Transferable.of(config), PYROSCOPE_CONFIG)
            .withCommand(PYROSCOPE_ARGS)
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
     *
     * The endpoint gets the lowest gateway priority, so the container keeps its default route on
     * the network its ports are published from. Without it a Linux Docker engine moves the default
     * route to whichever network's name sorts first, and Testcontainers names a network with a
     * random UUID, so most networks sort before `bridge`: the published port then stops answering.
     */
    fun attachAsService(
        container: GenericContainer<*>,
        networkId: String,
        alias: String,
    ) {
        val docker = DockerClientFactory.instance().client()
        val engineApi = docker.versionCmd().exec().apiVersion
        check(apiAtLeast(engineApi, GATEWAY_PRIORITY_API)) {
            "Docker engine API $engineApi ignores $GATEWAY_PRIORITY (needs $GATEWAY_PRIORITY_API or later, Docker 28)"
        }
        val endpoint = ContainerNetwork().withAliases(alias)
        DockerObjectAccessor.overrideRawValue(endpoint, GATEWAY_PRIORITY, LOWEST_GATEWAY_PRIORITY)
        docker
            .connectToNetworkCmd()
            .withContainerId(container.containerId)
            .withNetworkId(networkId)
            .withContainerNetwork(endpoint)
            .exec()
    }

    /** Whether the Docker API version [actual] (`major.minor`) is [required] or later. */
    private fun apiAtLeast(
        actual: String,
        required: String,
    ): Boolean {
        val parse = { v: String -> v.split(".").map { it.toInt() } }
        val (aMajor, aMinor) = parse(actual)
        val (rMajor, rMinor) = parse(required)
        return aMajor > rMajor || (aMajor == rMajor && aMinor >= rMinor)
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
