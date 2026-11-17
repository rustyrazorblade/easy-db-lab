package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.withClusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.agents.JavaAgent
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.stress.StressTarget
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.ExecListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A stress Job from the real [DefaultStressJobService] runs on a cluster whose one node carries no
 * easy-db-lab label, with the role selectors a brought cluster gets ([RoleSelectors.ANYWHERE]): the
 * pod is scheduled, its agent init containers copy both jars out of their images, and the stress
 * container sees them under [Constants.Kit.AGENTS_MOUNT]. Before the agents came from images, the
 * pod needed a `type=app` node and the AMI's host paths.
 *
 * The stress image is busybox standing in for cassandra-easy-stress (published for amd64 only), so
 * the test runs on any host; the pod, its placement, sidecar and agent delivery are the service's.
 * The Pyroscope agent image is built from the packaged Dockerfile under the tag the service names,
 * so the test does not depend on that tag being published. It uses its own K3s container, for the
 * preloaded images.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StressJobAnywhereIntegrationTest {
    private lateinit var k3s: K3sContainer
    private lateinit var client: KubernetesClient
    private lateinit var service: DefaultStressJobService

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
                        SharedK3s.BUSYBOX_IMAGE,
                        SIDECAR_IMAGE,
                        JavaAgent.OTEL.image,
                        JavaAgent.PYROSCOPE.image,
                    )
                }
        k3s.start()
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        val provider = mock<K8sClientProvider>()
        whenever(provider.createClient()).thenAnswer {
            KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        }
        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(
            ClusterState(
                name = "stress-anywhere",
                versions = mutableMapOf(),
                // The sidecar configuration names the store; nothing here reads or writes it.
                s3Bucket = "easy-db-lab-test",
                initConfig = InitConfig(region = "us-west-2", name = "stress-anywhere"),
                roleSelectors = RoleSelectors.ANYWHERE,
            ),
        )
        service =
            DefaultStressJobService(
                k8sService = DefaultK8sService(provider, EventBus()),
                clusterStateManager = stateManager,
                eventBus = EventBus(),
                templateService = TemplateService(stateManager, mock<User>()),
                ecrPullSecrets = mock<ImagePullSecretService>().also { whenever(it.ensureFor(any(), any())).thenReturn("") },
                podReadyPollInterval = Duration.ofSeconds(POD_POLL_SECONDS),
            )
    }

    @AfterAll
    fun teardown() {
        client.close()
        k3s.stop()
    }

    @Test
    fun `a stress Job runs on an unlabelled node with both agent jars delivered`() {
        val node =
            client
                .nodes()
                .list()
                .items
                .single()
        assertThat(node.metadata.labels).doesNotContainKey(Constants.NODE_TYPE_LABEL)

        val started =
            withClusterDiagnostics(client, Constants.Stress.NAMESPACE) {
                service
                    .startJob(
                        StressJobConfig(
                            jobName = JOB_NAME,
                            image = SharedK3s.BUSYBOX_IMAGE,
                            args = listOf("sh", "-c", "sleep 600"),
                            target = StressTarget("cassandra-pods-nodeport.default.svc", Constants.Kit.CassandraPods.CQL_NODE_PORT, "dc1"),
                        ),
                    ).getOrThrow()
            }

        assertThat(started.host).isEqualTo(node.metadata.name)
        val listing = exec(podName(), "ls", "-l", Constants.Kit.AGENTS_MOUNT)
        assertThat(listing).contains(JavaAgent.OTEL.fileName, JavaAgent.PYROSCOPE.fileName)
        assertThat(exec(podName(), "sh", "-c", "test -s ${JavaAgent.OTEL.jarPath()} && test -s ${JavaAgent.PYROSCOPE.jarPath()}"))
            .isEmpty()
    }

    private fun JavaAgent.jarPath(): String = "${Constants.Kit.AGENTS_MOUNT}/$fileName"

    private fun podName(): String =
        client
            .pods()
            .inNamespace(Constants.Stress.NAMESPACE)
            .withLabel("job-name", JOB_NAME)
            .list()
            .items
            .single()
            .metadata.name

    /** Runs [command] in the stress container and returns its stdout, failing on a non-zero exit. */
    private fun exec(
        pod: String,
        vararg command: String,
    ): String {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val done = CompletableFuture<Int>()
        client
            .pods()
            .inNamespace(Constants.Stress.NAMESPACE)
            .withName(pod)
            .inContainer(STRESS_CONTAINER)
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
                assertThat(status).describedAs("exit of ${command.toList()}: $err").isEqualTo(0)
            }
        return out.toString()
    }

    private companion object {
        const val JOB_NAME = "stress-anywhere"
        const val STRESS_CONTAINER = "stress"
        const val SIDECAR_IMAGE = "otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}"
        const val PYROSCOPE_DOCKERFILE = "docker/pyroscope-java-agent/Dockerfile"
        const val CQL_PORT = 30942
        const val POD_POLL_SECONDS = 15L
        const val EXEC_TIMEOUT_SECONDS = 60L
    }
}
