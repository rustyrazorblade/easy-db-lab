package com.rustyrazorblade.easydblab.kits

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.DefaultK8sStorageOperations
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.PersistentVolumeConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException
import io.fabric8.kubernetes.client.dsl.ExecListener
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the real `cassandra-pods` kit on a real K3s node labelled as a db node: the kit's own
 * rendered Service and StatefulSet manifests, applied the way `start` applies them, reach UN,
 * answer CQL on the NodePort value, keep their claims across a `stop` (label-selected delete of the
 * StatefulSet, Services and ConfigMap) and a second `start`, and serve the written row again.
 *
 * It uses its own K3s container because it creates cluster-scoped PersistentVolumes and a db-node
 * label. The CQL client is `cqlsh` inside the pod, dialing the node IP on the NodePort, which
 * crosses the NodePort Service exactly as an outside client does. K3s's single node stands for the
 * cluster's one db node (`--nodes 1`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CassandraPodsKitIntegrationTest {
    private val k3s: K3sContainer =
        K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
            .withPrivilegedMode(true)
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig!!
                    .withCgroupnsMode("host")
                    .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
            }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
            .withEnv("K3S_SNAPSHOTTER", "native")
            .let { (it as K3sContainer).withPreloadedImages(CASSANDRA_IMAGE, agentImage()) }

    private lateinit var client: KubernetesClient
    private lateinit var fixture: BuiltinKitFixture

    @BeforeAll
    fun setup() {
        k3s.start()
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        val node =
            client
                .nodes()
                .list()
                .items
                .single()
        client
            .nodes()
            .withName(node.metadata.name)
            .edit { n ->
                n.metadata.labels[Constants.NODE_TYPE_LABEL] = "db"
                n.metadata.labels[Constants.NODE_ORDINAL_LABEL] = "0"
                n
            }
        k3s.execInContainer("mkdir", "-p", PV_PATH)
        fixture =
            BuiltinKitFixture(
                "cassandra-pods",
                TemplateService(
                    ClusterStateManager(File(Files.createTempDirectory("cassandra-pods-it").toFile(), "state.json")),
                    mock<User>(),
                ),
            )
        createPersistentVolume()
        client
            .resource(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("cassandra-pods-jmx-rules")
                    .withNamespace("default")
                    .addToLabels("easydblab/kit", "cassandra-pods")
                    .endMetadata()
                    .addToData("cassandra-jmx-rules.yaml", fixture.resource("cassandra-jmx-rules.yaml"))
                    .build(),
            ).create()
    }

    @AfterAll
    fun teardown() {
        client.close()
        k3s.stop()
    }

    @Test
    fun `reaches UN, answers CQL on the NodePort, and keeps its data across stop and start`() {
        start()
        waitForUn()

        assertThat(cql("SELECT release_version FROM system.local")).contains("5.0")
        cql("CREATE KEYSPACE IF NOT EXISTS edl WITH replication = {'class':'SimpleStrategy','replication_factor':1}")
        cql("CREATE TABLE IF NOT EXISTS edl.t (k int PRIMARY KEY, v text)")
        cql("INSERT INTO edl.t (k, v) VALUES (1, 'kept')")

        stop()

        assertThat(
            client
                .apps()
                .statefulSets()
                .inNamespace("default")
                .list()
                .items,
        ).isEmpty()
        assertThat(
            client
                .services()
                .inNamespace("default")
                .withLabel("easydblab/kit", "cassandra-pods")
                .list()
                .items,
        ).isEmpty()
        assertThat(
            client
                .configMaps()
                .inNamespace("default")
                .withName("cassandra-pods-jmx-rules")
                .get(),
        ).isNull()
        assertThat(
            client
                .pods()
                .inNamespace("default")
                .withLabel("easydblab/kit", "cassandra-pods")
                .list()
                .items,
        ).isEmpty()
        assertThat(
            client
                .persistentVolumeClaims()
                .inNamespace("default")
                .list()
                .items,
        ).isNotEmpty()

        recreateJmxConfigMap()
        start()
        waitForUn()

        assertThat(cql("SELECT v FROM edl.t WHERE k = 1")).contains("kept")
    }

    /** The kit's manifests as `start` applies them: rendered, then the resolved image filled in. */
    private fun renderedKitObjects(): List<HasMetadata> {
        val args = mapOf("CASSANDRA_NODES" to "1")
        return listOf("headless-service.yaml.template", "nodeport-service.yaml.template", "statefulset.yaml.template")
            .flatMap { fixture.render(it, args) }
            .map { obj ->
                // Same fill-in apply-statefulset.sh does after the start check resolves the image.
                val yaml =
                    Serialization
                        .asYaml(obj)
                        .replace("@RESOLVED_IMAGE@", CASSANDRA_IMAGE)
                        .replace("@RESOLVED_BUILD@", "5.0")
                Serialization.unmarshal(yaml, HasMetadata::class.java)
            }
    }

    private fun start() {
        renderedKitObjects().forEach { client.resource(it).inNamespace("default").serverSideApply() }
    }

    /** What the kit's `stop` step does: delete the StatefulSet, Services and ConfigMap by label. */
    private fun stop() {
        val selector = "easydblab/kit" to "cassandra-pods"
        client
            .apps()
            .statefulSets()
            .inNamespace("default")
            .withLabel(selector.first, selector.second)
            .delete()
        client
            .services()
            .inNamespace("default")
            .withLabel(selector.first, selector.second)
            .delete()
        client
            .configMaps()
            .inNamespace("default")
            .withLabel(selector.first, selector.second)
            .delete()
        client
            .pods()
            .inNamespace("default")
            .withLabel(selector.first, selector.second)
            .waitUntilCondition({ it == null }, POD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun recreateJmxConfigMap() {
        client
            .resource(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("cassandra-pods-jmx-rules")
                    .withNamespace("default")
                    .addToLabels("easydblab/kit", "cassandra-pods")
                    .endMetadata()
                    .addToData("cassandra-jmx-rules.yaml", fixture.resource("cassandra-jmx-rules.yaml"))
                    .build(),
            ).create()
    }

    private fun waitForUn() {
        try {
            client
                .pods()
                .inNamespace("default")
                .withName("cassandra-pods-0")
                .waitUntilReady(POD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: KubernetesClientTimeoutException) {
            throw AssertionError("cassandra-pods-0 never became ready\n${podDiagnostics()}", e)
        }
        // Pod Ready only means the readiness probe passed. Like the kit's wait-un.sh, poll until
        // the node lists itself UN, and keep nodetool's own output if it never does.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UN_TIMEOUT_SECONDS)
        var last: ExecResult
        do {
            last = execResult("nodetool", "status")
            if (last.exitCode == 0 && last.out.lines().count { it.startsWith("UN") } == 1) return
            Thread.sleep(UN_POLL_MILLIS)
        } while (System.nanoTime() < deadline)
        throw AssertionError(
            "node never listed UN; exit=${last.exitCode}\nstdout=${last.out}\nstderr=${last.err}\n${podDiagnostics()}",
        )
    }

    /** Everything needed to see why the pod is not ready: status, container states, events and logs. */
    private fun podDiagnostics(): String {
        val sb = StringBuilder()
        val pod =
            client
                .pods()
                .inNamespace("default")
                .withName("cassandra-pods-0")
                .get()
        if (pod == null) {
            sb.appendLine("pod cassandra-pods-0 does not exist")
        } else {
            sb.appendLine("phase=${pod.status.phase} reason=${pod.status.reason} message=${pod.status.message}")
            pod.status.conditions.forEach {
                sb.appendLine("condition ${it.type}=${it.status} reason=${it.reason} message=${it.message}")
            }
            (pod.status.initContainerStatuses + pod.status.containerStatuses).forEach {
                sb.appendLine("container ${it.name} ready=${it.ready} restarts=${it.restartCount}")
                sb.appendLine("  state=${it.state}")
                sb.appendLine("  lastState=${it.lastState}")
            }
        }
        client
            .v1()
            .events()
            .inNamespace("default")
            .list()
            .items
            .takeLast(EVENT_LINES)
            .forEach { sb.appendLine("event ${it.involvedObject.name} ${it.reason}: ${it.message}") }
        listOf("otel-agent", "cassandra").forEach { container ->
            val log =
                runCatching {
                    client
                        .pods()
                        .inNamespace("default")
                        .withName("cassandra-pods-0")
                        .inContainer(container)
                        .tailingLines(LOG_LINES)
                        .log
                }.getOrElse { "unavailable: ${it.message}" }
            sb.appendLine("--- last $LOG_LINES log lines of $container ---").appendLine(log)
        }
        return sb.toString()
    }

    /** Runs a CQL statement with cqlsh against the node IP and NodePort value, from inside the pod. */
    private fun cql(statement: String): String {
        val hostIp = pod().status.hostIP
        return exec(
            "cqlsh",
            hostIp,
            Constants.Kit.CassandraPods.CQL_NODE_PORT
                .toString(),
            "-e",
            statement,
        )
    }

    private fun pod(): Pod =
        client
            .pods()
            .inNamespace("default")
            .withName("cassandra-pods-0")
            .get()

    private class ExecResult(
        val exitCode: Int,
        val out: String,
        val err: String,
    )

    private fun exec(vararg command: String): String {
        val result = execResult(*command)
        assertThat(result.exitCode).describedAs("exit of ${command.toList()}: ${result.err}").isEqualTo(0)
        return result.out
    }

    private fun execResult(vararg command: String): ExecResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val done = CompletableFuture<Int>()
        client
            .pods()
            .inNamespace("default")
            .withName("cassandra-pods-0")
            .inContainer("cassandra")
            .writingOutput(out)
            .writingError(err)
            .usingListener(
                object : ExecListener {
                    override fun onClose(
                        code: Int,
                        reason: String?,
                    ) {
                        done.complete(code)
                    }

                    override fun onFailure(
                        t: Throwable?,
                        response: ExecListener.Response?,
                    ) {
                        done.completeExceptionally(t)
                    }
                },
            ).exec(*command)
            .use { watch ->
                done.get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val status = watch.exitCode().get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                return ExecResult(status, out.toString(), err.toString())
            }
    }

    private fun createPersistentVolume() {
        val provider: K8sClientProvider = mock()
        val controlHost = ClusterHost("127.0.0.1", "127.0.0.1", "control0", "az", "i-0")
        whenever(provider.createClient(controlHost)).thenAnswer {
            KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        }
        val ops = DefaultK8sStorageOperations(provider, mock<EventBus>())
        ops.ensureLocalStorageWfcClass(controlHost).getOrThrow()
        ops
            .createLocalPersistentVolumes(
                controlHost,
                PersistentVolumeConfig(
                    dbName = "cassandra-pods",
                    localPath = PV_PATH,
                    count = 1,
                    // The PV must offer what the kit's claim requests (its STORAGE_SIZE default),
                    // or the claim never binds and the pod stays Pending.
                    storageSize =
                        fixture.config.args
                            .single { it.variable == "STORAGE_SIZE" }
                            .default,
                    storageClass = Constants.K8s.LOCAL_STORAGE_WFC_CLASS,
                ),
            ).getOrThrow()
    }

    private companion object {
        const val CASSANDRA_IMAGE = "cassandra:5.0"
        const val PV_PATH = "/mnt/db1/cassandra-pods"
        const val POD_TIMEOUT_SECONDS = 600L
        const val EXEC_TIMEOUT_SECONDS = 120L
        const val UN_TIMEOUT_SECONDS = 300L
        const val UN_POLL_MILLIS = 5_000L
        const val LOG_LINES = 100
        const val EVENT_LINES = 40

        fun agentImage(): String =
            "${Constants.Kit.CassandraPods.AGENT_IMAGE_REPOSITORY}:${System.getProperty("easydblab.otelAgentVersion")}"
    }
}
