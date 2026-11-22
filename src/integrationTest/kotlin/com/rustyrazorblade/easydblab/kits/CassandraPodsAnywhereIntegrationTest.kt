package com.rustyrazorblade.easydblab.kits

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.K3sDiagnostics.withClusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.agents.JavaAgent
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.DefaultK8sService
import com.rustyrazorblade.easydblab.services.DefaultStressJobService
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.StressJobConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.stress.StressTarget
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.ExecListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The `cassandra-pods` kit and a cassandra-easy-stress Job on a one-node cluster whose node carries
 * no easy-db-lab label, as on a cluster easy-db-lab did not build: with the role selectors of such a
 * cluster ([RoleSelectors.ANYWHERE]) and its kit claims on K3s's `local-path` class, the kit's own
 * rendered manifests reach UN, and a stress Job from the real [DefaultStressJobService], aimed at the
 * kit's Service as `StressTargetResolver` aims it, runs to Succeeded.
 *
 * It uses its own K3s container, for the preloaded images. The Pyroscope agent image is built from
 * the packaged Dockerfile under the tag the service names, so the test does not depend on that tag
 * being published.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CassandraPodsAnywhereIntegrationTest {
    private lateinit var k3s: K3sContainer
    private lateinit var client: KubernetesClient
    private lateinit var fixture: BuiltinKitFixture

    @BeforeAll
    fun setup() {
        ImageFromDockerfile(JavaAgent.PYROSCOPE.image, false)
            .withDockerfile(Path.of(PYROSCOPE_DOCKERFILE))
            .get()
        k3s =
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
                .withEnv("K3S_SNAPSHOTTER", "native")
                .let {
                    (it as K3sContainer).withPreloadedImages(
                        CASSANDRA_IMAGE,
                        Constants.Stress.IMAGE,
                        SIDECAR_IMAGE,
                        JavaAgent.OTEL.image,
                        JavaAgent.PYROSCOPE.image,
                        LOCAL_PATH_PROVISIONER_IMAGE,
                        LOCAL_PATH_HELPER_IMAGE,
                    )
                }
        k3s.start()
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        fixture =
            BuiltinKitFixture(
                "cassandra-pods",
                TemplateService(
                    ClusterStateManager(File(Files.createTempDirectory("cassandra-pods-anywhere").toFile(), "state.json")),
                ),
                kitStorageClass = LOCAL_PATH_CLASS,
                roleSelectors = RoleSelectors.ANYWHERE,
            )
        client
            .resource(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("cassandra-pods-jmx-rules")
                    .withNamespace(NAMESPACE)
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
    fun `the kit reaches UN on an unlabelled node and a stress Job against it succeeds`() {
        assertThat(
            client
                .nodes()
                .list()
                .items
                .single()
                .metadata.labels,
        ).doesNotContainKey(Constants.NODE_TYPE_LABEL)

        renderedKitObjects().forEach { client.resource(it).inNamespace(NAMESPACE).serverSideApply() }
        waitForUn()

        withClusterDiagnostics(client, NAMESPACE) {
            stressService()
                .startJob(
                    StressJobConfig(
                        jobName = JOB_NAME,
                        image = Constants.Stress.IMAGE,
                        args = STRESS_ARGS,
                        target = StressTarget(KIT_SERVICE, Constants.Kit.CassandraPods.CQL_NODE_PORT, "dc1"),
                    ),
                ).getOrThrow()
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(NAMESPACE)
                .withName(JOB_NAME)
                .waitUntilCondition(
                    { job -> (job?.status?.succeeded ?: 0) > 0 || (job?.status?.failed ?: 0) > 0 },
                    JOB_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
        }

        val job =
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(NAMESPACE)
                .withName(JOB_NAME)
                .get()
        assertThat(job.status.succeeded)
            .describedAs("stress Job did not succeed\n${stressLog()}\n${clusterDiagnostics(client, NAMESPACE)}")
            .isEqualTo(1)
    }

    /** The kit's manifests as `start` applies them: rendered, then the resolved image filled in. */
    private fun renderedKitObjects(): List<HasMetadata> =
        listOf("headless-service.yaml.template", "nodeport-service.yaml.template")
            .flatMap { fixture.render(it, mapOf("CASSANDRA_NODES" to "1")) } +
            fixture.renderFilled(
                "statefulset-image.yaml.template",
                mapOf("CASSANDRA_NODES" to "1"),
                strings = mapOf("RESOLVED_IMAGE" to CASSANDRA_IMAGE, "RESOLVED_BUILD" to "5.0"),
                yaml = KitPodResources.LOCAL,
            )

    private fun stressService(): DefaultStressJobService {
        val provider = mock<K8sClientProvider>()
        whenever(provider.createClient()).thenAnswer {
            KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        }
        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(
            ClusterState(
                name = "cassandra-pods-anywhere",
                // The sidecar configuration names the store; nothing here reads or writes it.
                s3Bucket = "easy-db-lab-test",
                initConfig = InitConfig(region = "us-west-2", name = "cassandra-pods-anywhere"),
                roleSelectors = RoleSelectors.ANYWHERE,
            ),
        )
        return DefaultStressJobService(
            k8sService = DefaultK8sService(provider, EventBus()),
            clusterStateManager = stateManager,
            eventBus = EventBus(),
            templateService = TemplateService(stateManager),
            podReadyPollInterval = Duration.ofSeconds(POD_POLL_SECONDS),
        )
    }

    private fun waitForUn() {
        withClusterDiagnostics(client, NAMESPACE) {
            client
                .pods()
                .inNamespace(NAMESPACE)
                .withName(CASSANDRA_POD)
                .waitUntilReady(POD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UN_TIMEOUT_SECONDS)
        var last = ""
        do {
            last = runCatching { nodetoolStatus() }.getOrElse { it.message.orEmpty() }
            if (last.lines().count { it.startsWith("UN") } == 1) return
            Thread.sleep(UN_POLL_MILLIS)
        } while (System.nanoTime() < deadline)
        throw AssertionError("node never listed UN:\n$last\n${clusterDiagnostics(client, NAMESPACE)}")
    }

    private fun stressLog(): String =
        runCatching {
            val pod =
                client
                    .pods()
                    .inNamespace(NAMESPACE)
                    .withLabel("job-name", JOB_NAME)
                    .list()
                    .items
                    .single()
            client
                .pods()
                .inNamespace(NAMESPACE)
                .withName(pod.metadata.name)
                .inContainer("stress")
                .tailingLines(LOG_LINES)
                .log
        }.getOrElse { "stress log unavailable: ${it.message}" }

    /** `nodetool status` in the Cassandra container; fails on a non-zero exit. */
    private fun nodetoolStatus(): String {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val done = CompletableFuture<Int>()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(CASSANDRA_POD)
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
            ).exec("nodetool", "status")
            .use { watch ->
                done.get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val status = watch.exitCode().get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                check(status == 0) { "nodetool status exited $status: $err" }
            }
        return out.toString()
    }

    private companion object {
        const val NAMESPACE = "default"
        const val CASSANDRA_IMAGE = "cassandra:5.0"
        const val CASSANDRA_POD = "cassandra-pods-0"
        const val SIDECAR_IMAGE = "otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}"
        const val PYROSCOPE_DOCKERFILE = "docker/pyroscope-java-agent/Dockerfile"

        /** K3s's bundled dynamic provisioner, standing in for the class `init --kit-storage-class` names. */
        const val LOCAL_PATH_CLASS = "local-path"
        const val LOCAL_PATH_PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.34"
        const val LOCAL_PATH_HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0"

        /** What `StressTargetResolver` resolves the running kit to: its CQL Service, by DNS name. */
        const val KIT_SERVICE = "cassandra-pods-nodeport.default.svc"
        const val JOB_NAME = "stress-cassandra-pods-anywhere"
        val STRESS_ARGS =
            listOf(
                "run",
                "KeyValue",
                "-n",
                "2000",
                "--replication",
                "{'class':'SimpleStrategy','replication_factor':1}",
                "--host",
                KIT_SERVICE,
            )
        const val POD_POLL_SECONDS = 15L
        const val POD_TIMEOUT_SECONDS = 600L
        const val UN_TIMEOUT_SECONDS = 300L
        const val UN_POLL_MILLIS = 5_000L
        const val JOB_TIMEOUT_SECONDS = 600L
        const val EXEC_TIMEOUT_SECONDS = 120L
        const val LOG_LINES = 100
    }
}
