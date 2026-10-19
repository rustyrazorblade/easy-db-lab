package com.rustyrazorblade.easydblab.kits

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Applies the SingleStore kit's manifests to a real K3s cluster, as `kit install` and `start` do: the
 * vendored CRD, the operator RBAC and Deployment, the MemsqlCluster the start step renders (through
 * the packaged shell library, with the run-time sizes filled in), and the NodePort Service.
 *
 * The cluster has its own container because the kit creates cluster-scoped objects (the CRD, a
 * ClusterRole and its binding). The operator image is not preloaded and need not run: the test
 * checks that the API server accepts every object as the kit writes it, that the stored cluster keeps
 * the computed sizes as numbers, and that the operator pod is scheduled onto the control-plane node.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SingleStoreKitIntegrationTest {
    companion object {
        private const val WAIT_SECONDS = 120L
        private const val NAMESPACE = "default"

        @Container
        @JvmStatic
        val k3s: K3sContainer =
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    requireNotNull(cmd.hostConfig) { "the K3s container has no host config" }
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
                .withEnv("K3S_SNAPSHOTTER", "native")
                .let { (it as K3sContainer).withPreloadedImages() }
    }

    private val workDir: File = Files.createTempDirectory("singlestore-kit").toFile()
    private val kit = BuiltinKitFixture("singlestore", TemplateService(ClusterStateManager(File(workDir, "state.json")), mock<User>()))
    private lateinit var client: KubernetesClient

    @BeforeAll
    fun installTheOperator() {
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        client
            .serviceAccounts()
            .inNamespace(NAMESPACE)
            .withName("default")
            .waitUntilCondition({ it != null }, WAIT_SECONDS, TimeUnit.SECONDS)
        for (manifest in listOf("crd.yaml", "operator-rbac.yaml", "operator-deployment.yaml.template")) {
            kit.render(manifest).forEach(::apply)
        }
        client
            .apiextensions()
            .v1()
            .customResourceDefinitions()
            .withName("memsqlclusters.memsql.com")
            .waitUntilCondition(
                { crd ->
                    crd
                        ?.status
                        ?.conditions
                        .orEmpty()
                        .any { it.type == "Established" && it.status == "True" }
                },
                WAIT_SECONDS,
                TimeUnit.SECONDS,
            )
    }

    @AfterAll
    fun closeClient() {
        client.close()
        workDir.deleteRecursively()
    }

    private fun apply(resource: HasMetadata) {
        client.resource(resource).forceConflicts().serverSideApply()
    }

    @Test
    fun `the operator RBAC is in place and its pod is scheduled on the control-plane node`() {
        assertThat(
            client
                .serviceAccounts()
                .inNamespace(NAMESPACE)
                .withName("singlestore-operator")
                .get(),
        ).isNotNull()
        assertThat(
            client
                .rbac()
                .clusterRoleBindings()
                .withName("singlestore-operator")
                .get()
                .subjects
                .single()
                .namespace,
        ).isEqualTo(NAMESPACE)

        val pod =
            client
                .pods()
                .inNamespace(NAMESPACE)
                .withLabel("name", "singlestore-operator")
                .informOnCondition { pods -> pods.any { !it.spec.nodeName.isNullOrBlank() } }
                .get(WAIT_SECONDS, TimeUnit.SECONDS)
                .single()
        val node = client.nodes().withName(pod.spec.nodeName).get()

        assertThat(node.metadata.labels).containsEntry("node-role.kubernetes.io/control-plane", "true")
        assertThat(pod.spec.serviceAccountName).isEqualTo("singlestore-operator")
    }

    /** The MemsqlCluster as the start step renders it, through the packaged library, for 2 leaves. */
    private fun renderedCluster(): String {
        val kitDir = File(workDir, "kit").apply { mkdirs() }
        File(kitDir, "memsqlcluster.yaml").writeText(kit.renderText("memsqlcluster.yaml.template"))
        val lib = File(kitDir, "bin/lib").apply { mkdirs() }
        for (file in listOf("singlestore-cluster.sh", "singlestore-sizing.sh", "singlestore-sql.sh")) {
            File(lib, file).writeText(kit.resource("bin/lib/$file"))
        }
        val process =
            ProcessBuilder("bash", "-c", "set -euo pipefail; source bin/lib/singlestore-cluster.sh; ss_render_cluster")
                .directory(kitDir)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(
                        mapOf(
                            "REDUNDANCY" to "2",
                            "LEAF_COUNT" to "1",
                            "LEAF_CORES" to "4",
                            "LEAF_MEMORY_MB" to "51520",
                            "STORAGE_GB" to "10240",
                            "AGGREGATOR_MEMORY_MB" to "8192",
                        ),
                    )
                }.start()
        check(process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) { "rendering the cluster did not finish" }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "rendering the cluster failed: $output" }
        return output
    }

    @Test
    fun `the cluster the start step renders is stored with its sizes, scheduling and labels`() {
        client.load(renderedCluster().byteInputStream()).items().forEach(::apply)

        val stored =
            client
                .genericKubernetesResources("memsql.com/v1alpha1", "MemsqlCluster")
                .inNamespace(NAMESPACE)
                .withName("singlestore")
                .get()

        assertThat(stored.get<Int>("spec", "redundancyLevel")).isEqualTo(2)
        assertThat(stored.get<Int>("spec", "leafSpec", "count")).isEqualTo(1)
        assertThat(stored.get<Int>("spec", "leafSpec", "cores")).isEqualTo(4)
        assertThat(stored.get<Int>("spec", "leafSpec", "memoryMB")).isEqualTo(51520)
        assertThat(stored.get<Int>("spec", "leafSpec", "storageGB")).isEqualTo(10240)
        assertThat(stored.get<Int>("spec", "masterAggregatorSpec", "memoryMB")).isEqualTo(8192)
        assertThat(stored.get<Int>("spec", "childAggregatorSpec", "memoryMB")).isEqualTo(8192)
        assertThat(stored.get<Map<String, String>>("spec", "childAggregatorSpec", "objectMetaOverrides", "labels"))
            .containsEntry("easydblab/role", "aggregator")
            .containsEntry("easydblab/tier", "aggregator")
        assertThat(stored.get<Map<String, Any>>("spec")).doesNotContainKey("aggregatorSpec")
        assertThat(stored.get<String>("spec", "licenseSecret", "name")).isEqualTo("singlestore-license")
        assertThat(stored.get<Map<String, String>>("spec", "leafSpec", "objectMetaOverrides", "labels"))
            .containsEntry("easydblab/role", "leaf")
        for (group in listOf("leaf-ag2", "master", "aggregator")) {
            assertThat(
                stored.get<List<Any>>(
                    "spec",
                    "schedulingDetails",
                    group,
                    "affinity",
                    "podAntiAffinity",
                    "requiredDuringSchedulingIgnoredDuringExecution",
                ),
            ).describedAs(group).hasSize(1)
        }
    }

    @Test
    fun `the NodePort Service is accepted on 30306`() {
        kit.render("nodeport-service.yaml").forEach(::apply)

        val service =
            client
                .services()
                .inNamespace(NAMESPACE)
                .withName("singlestore-nodeport")
                .get()

        assertThat(service.spec.type).isEqualTo("NodePort")
        assertThat(
            service.spec.ports
                .single()
                .nodePort,
        ).isEqualTo(30306)
        assertThat(service.spec.selector).containsEntry("easydblab/tier", "aggregator")
    }
}
