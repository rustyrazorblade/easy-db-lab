package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.Yaml
import com.github.dockerjava.api.model.Network.Ipam
import com.rustyrazorblade.easydblab.SharedK3s
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Network
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import java.time.Duration

/**
 * Boots a real K3s with the datastore drop-in that `start-k3s-server.sh` writes, and proves the
 * server runs on embedded etcd, not kine/sqlite, and that its state survives a restart.
 *
 * [SharedK3s.newContainer] reads the drop-in out of the packaged script, so the test fails when the
 * script's settings stop being valid K3s configuration. It has its own container, not the shared
 * cluster, because it needs a fixed address and the restart changes the container.
 */
class K3sEmbeddedEtcdIntegrationTest {
    private companion object {
        const val DATA_DIR = "/var/lib/rancher/k3s"
        const val READY_TIMEOUT_SECONDS = 180L
        const val POLL_MILLIS = 2_000L
        const val LOG_TAIL_CHARS = 4_000

        // A user-defined network with a fixed address: on the default bridge a restarted container
        // can come back with another IP, and etcd then refuses the peer URL it stored, which an EC2
        // node (it keeps its private IP across a reboot) never does.
        const val SUBNET = "172.31.250.0/28"
        const val GATEWAY = "172.31.250.1"
        const val CONTAINER_IP = "172.31.250.10"

        // Pins the address K3s advertises, as an EC2 node's single private IP does. Once any class
        // in the JVM has called Testcontainers.exposeHostPorts, every new container also joins the
        // port-forwarding container's default-bridge network, and K3s then picked the bridge
        // address, which a restart changes.
        const val NODE_IP_DROP_IN_PATH = "/etc/rancher/k3s/config.yaml.d/10-node-ip.yaml"
    }

    /** A network whose subnet is fixed, so [CONTAINER_IP] can be assigned statically. */
    private fun fixedSubnetNetwork(): Network =
        Network
            .builder()
            .createNetworkCmdModifier { cmd ->
                cmd.withIpam(Ipam().withConfig(Ipam.Config().withSubnet(SUBNET).withGateway(GATEWAY)))
            }.build()

    private fun K3sContainer.kubectl(vararg args: String): String {
        val result = execInContainer("kubectl", *args)
        check(result.exitCode == 0) { "kubectl ${args.joinToString(" ")} exited ${result.exitCode}: ${result.stderr}" }
        return result.stdout.trim()
    }

    private fun K3sContainer.sh(command: String): String = execInContainer("sh", "-c", command).stdout.trim()

    /**
     * Polls until the restarted API server answers with a healthy etcd, then until the node reports
     * Ready. The first condition comes first because the node object keeps its old Ready status
     * until the node controller's grace period ends, so Ready alone does not prove the restart took.
     */
    private fun K3sContainer.awaitRestarted() {
        pollUntil("the API server did not report etcd healthy", this) {
            execInContainer("kubectl", "get", "--raw", "/readyz?verbose").stdout.contains("[+]etcd ok")
        }
        pollUntil("the node was not Ready", this) {
            kubectl("get", "nodes", "-o", "jsonpath={.items[*].status.conditions[?(@.type=='Ready')].status}") == "True"
        }
    }

    private fun pollUntil(
        failure: String,
        container: K3sContainer,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(READY_TIMEOUT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(POLL_MILLIS)
        }
        error("$failure $READY_TIMEOUT_SECONDS seconds after the restart. K3s log tail:\n${container.logs.takeLast(LOG_TAIL_CHARS)}")
    }

    @Test
    fun `K3s with the datastore drop-in runs on etcd and keeps its objects across a restart`() {
        val network = fixedSubnetNetwork()
        val container = SharedK3s.newContainer()
        container.withNetwork(network)
        container.withCreateContainerCmdModifier { cmd -> cmd.withIpv4Address(CONTAINER_IP) }
        container.withCopyToContainer(
            Transferable.of(Yaml.default.encodeToString(K3sNodeIpConfig(nodeIp = CONTAINER_IP))),
            NODE_IP_DROP_IN_PATH,
        )

        network.use { _ ->
            container.use {
                it.start()

                assertThat(it.kubectl("get", "--raw", "/readyz?verbose")).contains("[+]etcd ok")
                assertThat(it.kubectl("get", "nodes", "-l", "node-role.kubernetes.io/etcd=true", "-o", "name")).startsWith("node/")
                assertThat(it.sh("test -d $DATA_DIR/server/db/etcd/member && echo member")).isEqualTo("member")
                assertThat(it.sh("test -e $DATA_DIR/server/db/state.db && echo sqlite || echo none")).isEqualTo("none")

                it.kubectl("create", "configmap", "survives-restart", "--from-literal=k=v")
                it.dockerClient.restartContainerCmd(it.containerId).exec()
                assertThat(it.addressOn(network)).isEqualTo(CONTAINER_IP)
                it.awaitRestarted()

                assertThat(it.kubectl("get", "configmap", "survives-restart", "-o", "jsonpath={.data.k}")).isEqualTo("v")
            }
        }
    }

    /** The container's IPv4 address on [network], as Docker reports it now. */
    private fun K3sContainer.addressOn(network: Network): String? =
        dockerClient
            .inspectContainerCmd(containerId)
            .exec()
            .networkSettings
            ?.networks
            ?.values
            ?.firstOrNull { it.networkID == network.id }
            ?.ipAddress
}

/** The K3s config drop-in that pins the address the node advertises. */
@Serializable
private data class K3sNodeIpConfig(
    @SerialName("node-ip") val nodeIp: String,
)
