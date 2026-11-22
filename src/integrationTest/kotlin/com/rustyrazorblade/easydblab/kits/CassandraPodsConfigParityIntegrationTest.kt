package com.rustyrazorblade.easydblab.kits

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.cassandra.CassandraConfigPatch
import com.rustyrazorblade.easydblab.configuration.cassandra.CassandraPlacement
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.cassandraversions.AsfReleases
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Config parity between the two Cassandra paths: a release tarball's own `cassandra.yaml`, merged
 * in the real `cassandra-runtime` image once with the VM layers and once with the pod layers, must
 * differ only in what depends on where Cassandra runs (addresses, ports, paths, the snitch and the
 * seeds).
 *
 * - The VM render is what `cassandra update-config` produces: the common patch with the VM
 *   placement (`cassandra write-config`), the node's addresses added (`UpdateConfig`), merged by the
 *   packaged `edl_merge_cassandra_yaml` that `patch-config` runs.
 * - The pod render is `config/render-config.sh`, the `cassandra-render` init container, from the
 *   files the `cassandra-artifact` contributor writes.
 *
 * The tarball comes from the Apache CDN, so a merge change that reaches a key no patch names (a yq
 * that rewrites the whole file, a layer applied in the wrong order) fails here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CassandraPodsConfigParityIntegrationTest {
    private lateinit var fixture: BuiltinKitFixture
    private lateinit var runtimeImage: String

    @BeforeAll
    fun setup() {
        fixture =
            BuiltinKitFixture(
                "cassandra-pods",
                TemplateService(
                    ClusterStateManager(
                        File(createTempDirectory("cassandra-pods-parity").toFile(), "state.json"),
                    ),
                ),
            )
        runtimeImage = CassandraRuntimeTestImage.build(JAVA)
    }

    @Test
    fun `the VM and pod renders of a release's cassandra yaml differ only in placement keys`() {
        val (vm, pod) = render()

        val vmKeys = topLevel(vm)
        val podKeys = topLevel(pod)
        val differing = (vmKeys.keys + podKeys.keys).filter { key -> !same(vmKeys[key], podKeys[key]) }.toSet()

        assertThat(differing).describedAs("keys the two renders disagree on").isNotEmpty.isSubsetOf(PLACEMENT_KEYS)
        // The shared keys really are the workspace patch's, on both paths.
        for (render in listOf(vmKeys, podKeys)) {
            assertThat(render.scalar("num_tokens")).isEqualTo("${Constants.CassandraConfig.DEFAULT_TOKENS}")
            assertThat(render.scalar("trickle_fsync")).isEqualTo("true")
            assertThat(render.scalar("cluster_name")).isEqualTo(CLUSTER)
        }
        assertThat(podKeys.scalar("native_transport_port")).isEqualTo("${Constants.Kit.CassandraPods.CQL_NODE_PORT}")
    }

    /** The VM render and the pod render of the tarball's `cassandra.yaml`, in one runtime container. */
    private fun render(): Pair<String, String> {
        val common = CassandraConfigPatch.common(CLUSTER, Constants.CassandraConfig.DEFAULT_TOKENS)
        val vmPlacement = CassandraConfigPatch.placement(CassandraPlacement.Vm(seedIps = listOf(NODE_IP)))
        val podPlacement = CassandraConfigPatch.placement(CassandraPlacement.Pods(seedDns = Constants.Kit.CassandraPods.SEED_DNS))
        val vmAddresses = "listen_address: $NODE_IP\nrpc_address: $NODE_IP\nbroadcast_rpc_address: $NODE_IP\n"

        GenericContainer(DockerImageName.parse(runtimeImage))
            .withCommand("sleep", "infinity")
            .withCopyToContainer(Transferable.of(fixture.resource("config/edl-cassandra-config.sh")), "$CONFIG/edl-cassandra-config.sh")
            .withCopyToContainer(Transferable.of(fixture.resource("config/render-config.sh")), "$CONFIG/render-config.sh")
            .withCopyToContainer(Transferable.of(fixture.resource("config/cassandra.in.sh")), "$CONFIG/cassandra.in.sh")
            .withCopyToContainer(Transferable.of(common.toYaml() + "\n"), "$CONFIG/cassandra.patch.yaml")
            .withCopyToContainer(Transferable.of(podPlacement.toYaml() + "\n"), "$CONFIG/placement.patch.yaml")
            .withCopyToContainer(Transferable.of("dc=dc1\nrack=rack1\n"), "$CONFIG/cassandra-rackdc.properties")
            .withCopyToContainer(Transferable.of((common + vmPlacement).toYaml() + "\n"), "/tmp/vm/cassandra.patch.yaml")
            .withCopyToContainer(Transferable.of(vmAddresses), "/tmp/vm/addresses.yaml")
            .use { container ->
                container.start()
                val tarball = AsfReleases.downloadUrl(RELEASE)
                container.sh("mkdir -p /tmp/cassandra && curl -fsSL '$tarball' | tar -xz -C /tmp/cassandra --strip-components=1")
                container.sh(
                    ". $CONFIG/edl-cassandra-config.sh && mkdir -p /tmp/vm-out && " +
                        "edl_merge_cassandra_yaml /tmp/cassandra/conf/cassandra.yaml /tmp/vm-out/cassandra.yaml " +
                        "/tmp/vm/cassandra.patch.yaml /tmp/vm/addresses.yaml",
                )
                container.sh(
                    "EDL_CONFIG_DIR=$CONFIG EDL_CASSANDRA_HOME=/tmp/cassandra CASSANDRA_CONF=/tmp/pod POD_IP=$POD_IP HOST_IP=$NODE_IP " +
                        "sh $CONFIG/render-config.sh",
                )
                return container.sh("cat /tmp/vm-out/cassandra.yaml") to container.sh("cat /tmp/pod/cassandra.yaml")
            }
    }

    private fun GenericContainer<*>.sh(command: String): String {
        val result = execInContainer("sh", "-c", command)
        assertThat(result.exitCode).describedAs("`$command` failed:\n${result.stderr}").isEqualTo(0)
        return result.stdout
    }

    private fun topLevel(yaml: String): Map<String, YamlNode> =
        (Yaml.default.parseToYamlNode(yaml) as YamlMap).entries.entries.associate { (key, value) -> key.content to value }

    private fun Map<String, YamlNode>.scalar(key: String): String? = (this[key] as? YamlScalar)?.content

    private fun same(
        left: YamlNode?,
        right: YamlNode?,
    ): Boolean = if (left == null || right == null) left == right else left.equivalentContentTo(right)

    private companion object {
        const val RELEASE = "5.0.9"
        const val JAVA = "17"
        const val CLUSTER = "parity"
        const val CONFIG = "/tmp/edl-config"
        const val NODE_IP = "10.0.1.10"
        const val POD_IP = "10.42.0.7"

        /**
         * What may differ: the addresses, the native port, the data directories, the snitch and the
         * seeds. Every other key is the tarball's own or the workspace patch's on both paths.
         */
        val PLACEMENT_KEYS =
            setOf(
                "listen_address",
                "rpc_address",
                "broadcast_rpc_address",
                "native_transport_port",
                "hints_directory",
                "data_file_directories",
                "commitlog_directory",
                "saved_caches_directory",
                "endpoint_snitch",
                "seed_provider",
            )
    }
}
