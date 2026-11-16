package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.SharedK3s
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Boots a real K3s with the datastore drop-in that `start-k3s-server.sh` writes, and proves the
 * server runs on embedded etcd, not kine/sqlite, and that its state survives a restart.
 *
 * The drop-in is read out of the packaged script, so the test fails when the script's settings
 * stop being valid K3s configuration. It has its own container, not [SharedK3s], because the
 * drop-in must be in place before K3s first starts and the restart changes the container.
 */
class K3sEmbeddedEtcdIntegrationTest {
    private companion object {
        const val DROP_IN_PATH = "/etc/rancher/k3s/config.yaml.d/20-datastore.yaml"
        const val DATA_DIR = "/var/lib/rancher/k3s"
        const val SCRIPT_RESOURCE = "/com/rustyrazorblade/easydblab/services/start-k3s-server.sh"
        const val READY_TIMEOUT_SECONDS = 180L
        const val POLL_MILLIS = 2_000L
        const val LOG_TAIL_CHARS = 4_000
    }

    /** The heredoc body the server script writes to the datastore drop-in. */
    private fun datastoreDropIn(): String {
        val script =
            checkNotNull(this::class.java.getResourceAsStream(SCRIPT_RESOURCE)) { "$SCRIPT_RESOURCE is not packaged" }
                .use { it.readBytes().decodeToString() }
        val start = script.indexOf("cat > $DROP_IN_PATH <<'EOF'\n")
        check(start >= 0) { "start-k3s-server.sh does not write $DROP_IN_PATH" }
        return script.substring(start).substringAfter("<<'EOF'\n").substringBefore("\nEOF\n") + "\n"
    }

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
        val container =
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withEnv("K3S_SNAPSHOTTER", "native") as K3sContainer
        container.withCopyToContainer(Transferable.of(datastoreDropIn()), DROP_IN_PATH)

        container.use {
            it.start()

            assertThat(it.kubectl("get", "--raw", "/readyz?verbose")).contains("[+]etcd ok")
            assertThat(it.kubectl("get", "nodes", "-l", "node-role.kubernetes.io/etcd=true", "-o", "name")).startsWith("node/")
            assertThat(it.sh("test -d $DATA_DIR/server/db/etcd/member && echo member")).isEqualTo("member")
            assertThat(it.sh("test -e $DATA_DIR/server/db/state.db && echo sqlite || echo none")).isEqualTo("none")

            it.kubectl("create", "configmap", "survives-restart", "--from-literal=k=v")
            it.dockerClient.restartContainerCmd(it.containerId).exec()
            it.awaitRestarted()

            assertThat(it.kubectl("get", "configmap", "survives-restart", "-o", "jsonpath={.data.k}")).isEqualTo("v")
        }
    }
}
