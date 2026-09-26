package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Runs the Pyroscope server at the image the cluster deploys, with the configuration the cluster
 * renders, against S3 (LocalStack). It proves the rendered configuration is accepted by the real
 * binary (its parser rejects unknown keys), that a profile written with the cluster's tenant is
 * stored under `observability/profiles/` and is visible only to that tenant, and that the v2
 * metastore index — the only record of where the blocks are — survives a restart on the same data
 * volume, so the profile is still returned afterwards. It also proves that two clusters in the same
 * tenant, each with its own Pyroscope and metastore, write into the same `observability/profiles/`
 * directory at the same time and each keeps every profile it wrote.
 *
 * The data directory is a Docker volume, standing in for the control node's hostPath; a restarted
 * server reuses its cluster's volume. The only edit to the rendered configuration points the S3
 * client at LocalStack.
 */
class PyroscopeIndexDurabilityIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val DATA_MOUNT = "/data"
        const val SERVICE = "it-profiled"
        val WAIT: Duration = Duration.ofMinutes(2)

        /** How long the server's startup probe lets it take to answer `/ready`. */
        val STARTUP_PROBE_BUDGET: Duration =
            Duration.ofSeconds(
                Constants.PyroscopeProbes.STARTUP_PERIOD_SECONDS.toLong() *
                    Constants.PyroscopeProbes.STARTUP_FAILURE_THRESHOLD,
            )
        val POLL: Duration = Duration.ofSeconds(2)
        const val OTHER_SERVICE = "it-profiled-other"

        /** How long both clusters keep writing, so v2 compaction runs over the shared directory. */
        val CONCURRENT_WRITES: Duration = Duration.ofSeconds(60)
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "pyroscope-durability-${UUID.randomUUID().toString().take(8)}"
    private val dataVolume = newVolumeName()
    private val volumes = mutableListOf(dataVolume)
    private val state =
        ClusterState(
            name = "pyroscope-it",
            versions = mutableMapOf(),
            s3Bucket = bucket,
            dataBucket = "easy-db-lab-data-should-not-be-used",
            initConfig = InitConfig(region = SharedLocalStack.region(), tenant = TENANT),
        )
    private val http = HttpClient.newHttpClient()
    private val containers = mutableListOf<GenericContainer<*>>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun cleanUp() {
        containers.forEach { it.stop() }
        volumes.forEach { volume -> runCatching { docker.removeVolumeCmd(volume).exec() } }
    }

    private fun newVolumeName() = "pyroscope-data-${UUID.randomUUID().toString().take(8)}"

    /** The cluster's rendered config.yaml, with the S3 client pointed at LocalStack. */
    private fun testConfig(): String {
        val rendered = PyroscopeManifestBuilder(getKoin().get()).buildServerConfigMap().data.getValue("config.yaml")
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
     * Starts a Pyroscope server with [config] on the data [volume]; each cluster has its own.
     *
     * It must answer `/ready` within the Deployment's startup-probe budget, which is the window the
     * kubelet allows before it kills the server, so every start here also checks that budget.
     */
    private fun startPyroscope(
        config: String,
        volume: String = dataVolume,
    ): GenericContainer<*> {
        val pyroscope =
            GenericContainer(PyroscopeManifestBuilder.SERVER_IMAGE)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.withUser("root")
                    cmd.hostConfig?.withBinds(Bind(volume, Volume(DATA_MOUNT)))
                }.withCopyToContainer(Transferable.of(config), "/etc/pyroscope/config.yaml")
                .withCommand("-config.file=/etc/pyroscope/config.yaml")
                .withExposedPorts(PyroscopeManifestBuilder.SERVER_PORT)
                .waitingFor(
                    Wait
                        .forHttp("/ready")
                        .forPort(PyroscopeManifestBuilder.SERVER_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(STARTUP_PROBE_BUDGET),
                )
        pyroscope.start()
        containers.add(pyroscope)
        return pyroscope
    }

    private fun baseUrl(pyroscope: GenericContainer<*>) =
        "http://${pyroscope.host}:${pyroscope.getMappedPort(PyroscopeManifestBuilder.SERVER_PORT)}"

    private fun ingest(
        pyroscope: GenericContainer<*>,
        service: String = SERVICE,
    ) {
        val name = URLEncoder.encode("$service{cluster=${state.clusterLabelName()}}", Charsets.UTF_8)
        val request =
            HttpRequest
                .newBuilder(URI("${baseUrl(pyroscope)}/ingest?name=$name&format=folded"))
                .header(Constants.Observability.TENANT_HEADER, TENANT)
                .POST(HttpRequest.BodyPublishers.ofString("main;work;compute 100\nmain;work;io 50\n"))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
    }

    /** Total CPU samples [tenant] sees for [service] over the last hour. */
    private fun cpuTicks(
        pyroscope: GenericContainer<*>,
        tenant: String,
        service: String = SERVICE,
    ): Long {
        val query = URLEncoder.encode("process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"$service\"}", Charsets.UTF_8)
        val request =
            HttpRequest
                .newBuilder(URI("${baseUrl(pyroscope)}/pyroscope/render?query=$query&from=now-1h&until=now&format=json"))
                .header(Constants.Observability.TENANT_HEADER, tenant)
                .GET()
                .build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return Regex("\"numTicks\":(\\d+)")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toLong() ?: 0
    }

    private fun awaitTicks(
        pyroscope: GenericContainer<*>,
        service: String = SERVICE,
    ) {
        val deadline = System.nanoTime() + WAIT.toNanos()
        var ticks = cpuTicks(pyroscope, TENANT, service)
        while (ticks == 0L && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            ticks = cpuTicks(pyroscope, TENANT, service)
        }
        assertThat(ticks).describedAs("CPU samples tenant $TENANT sees for $service").isPositive()
    }

    /** CPU samples [service] has once two polls in a row agree, i.e. every write is queryable. */
    private fun settledTicks(
        pyroscope: GenericContainer<*>,
        service: String,
    ): Long {
        val deadline = System.nanoTime() + WAIT.toNanos()
        var previous = -1L
        var ticks = cpuTicks(pyroscope, TENANT, service)
        while ((ticks == 0L || ticks != previous) && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            previous = ticks
            ticks = cpuTicks(pyroscope, TENANT, service)
        }
        assertThat(ticks).describedAs("CPU samples tenant $TENANT sees for $service").isPositive().isEqualTo(previous)
        return ticks
    }

    /** Waits for [service] to show [expected] CPU samples again, and fails with what it shows. */
    private fun awaitExactTicks(
        pyroscope: GenericContainer<*>,
        service: String,
        expected: Long,
    ) {
        val deadline = System.nanoTime() + WAIT.toNanos()
        var ticks = cpuTicks(pyroscope, TENANT, service)
        while (ticks < expected && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            ticks = cpuTicks(pyroscope, TENANT, service)
        }
        assertThat(ticks).describedAs("CPU samples tenant $TENANT sees for $service after the restart").isEqualTo(expected)
    }

    private fun keysUnder(prefix: String): List<String> =
        s3
            .listObjectsV2(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { it.key() }

    @Test
    fun `a tenant's profiles land in the account bucket and stay queryable across a restart`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(dataVolume).exec()
        val config = testConfig()

        val first = startPyroscope(config)
        ingest(first)
        awaitTicks(first)
        assertThat(cpuTicks(first, "other")).describedAs("another tenant sees nothing").isZero()

        val profilesPrefix = ObservabilityStore.from(state).profilesPrefix() + "/"
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (keysUnder(profilesPrefix).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
        }
        assertThat(keysUnder(profilesPrefix)).isNotEmpty()
        assertThat(keysUnder("")).allMatch { it.startsWith(profilesPrefix) }

        first.dockerClient
            .stopContainerCmd(first.containerId)
            .withTimeout(30)
            .exec()
        val second = startPyroscope(config)
        awaitTicks(second)
    }

    @Test
    fun `two clusters in one tenant write profiles into the same directory and each keeps every profile`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        val otherVolume = newVolumeName().also { volumes.add(it) }
        docker.createVolumeCmd().withName(dataVolume).exec()
        docker.createVolumeCmd().withName(otherVolume).exec()
        val config = testConfig()

        // Two clusters, same bucket, prefix and tenant, each with its own server and metastore.
        val first = startPyroscope(config, dataVolume)
        val second = startPyroscope(config, otherVolume)

        // Both write at once, long enough for each server's compaction to run over the shared directory.
        val until = System.nanoTime() + CONCURRENT_WRITES.toNanos()
        while (System.nanoTime() < until) {
            ingest(first, SERVICE)
            ingest(second, OTHER_SERVICE)
            Thread.sleep(POLL.toMillis())
        }
        val firstTicks = settledTicks(first, SERVICE)
        val secondTicks = settledTicks(second, OTHER_SERVICE)
        val profilesPrefix = ObservabilityStore.from(state).profilesPrefix() + "/"
        assertThat(keysUnder("")).isNotEmpty().allMatch { it.startsWith(profilesPrefix) }

        // After a restart each cluster still has every sample it wrote: neither server deleted or
        // overwrote an object the other's index points at.
        listOf(first, second).forEach {
            it.dockerClient
                .stopContainerCmd(it.containerId)
                .withTimeout(30)
                .exec()
        }
        awaitExactTicks(startPyroscope(config, dataVolume), SERVICE, firstTicks)
        awaitExactTicks(startPyroscope(config, otherVolume), OTHER_SERVICE, secondTicks)
    }
}
