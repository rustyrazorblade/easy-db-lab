package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.encodeToString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.MountableFile

/**
 * Runs the real `start-k3s-server.sh` and `start-k3s-agent.sh` resources, as root, in a container
 * whose `/mnt/db1` is a real mount (a tmpfs) or is absent, with `systemctl` and the k3s installer
 * stubbed. The filesystem effects are real: what `/var/log/pods` becomes, what is copied, and the
 * kubelet configuration the script writes for k3s.
 */
class StartK3sScriptsIntegrationTest {
    companion object {
        private const val IMAGE = "ubuntu:24.04"
        private const val RESOURCE_DIR = "com/rustyrazorblade/easydblab/services"
        private const val EVICTION_CONFIG = "/etc/rancher/k3s/config.yaml.d/10-kubelet-eviction.yaml"
        private const val DATASTORE_CONFIG = "/etc/rancher/k3s/config.yaml.d/20-datastore.yaml"
        private const val KUBELET_LIMITS_CONFIG = "/etc/rancher/k3s/config.yaml.d/15-kubelet-limits.yaml"
        private const val ADMISSION_POLICY_CONFIG = "/etc/rancher/k3s/config.yaml.d/30-admission-policy.yaml"
        private const val CALLS_LOG = "/tmp/calls.log"
        private const val SERVER = "start-k3s-server.sh"
        private const val AGENT = "start-k3s-agent.sh"

        /**
         * systemctl reports no installed unit, so the script runs the (stubbed) installer. Each call
         * is logged to [CALLS_LOG] as `systemctl <args>`, one call a line.
         */
        private const val SYSTEMCTL_STUB = "#!/bin/sh\necho \"systemctl $*\" >> $CALLS_LOG\nexit 0\n"

        /** The k3s installer is not run; it logs the server and token it was given. */
        private const val INSTALLER_STUB =
            "#!/bin/sh\necho \"installer url=${'$'}K3S_URL token=${'$'}K3S_TOKEN\" >> /tmp/calls.log\nexit 0\n"

        /** The agent config `configure()` writes: the agent script reads the server and token from it. */
        private const val AGENT_CONFIG_PATH = "/etc/rancher/k3s/config.yaml"
        private const val AGENT_SERVER = "https://10.0.0.5:6443"
        private const val AGENT_TOKEN = "K10abc::server:def"

        /** Written as `configure()` writes it, by the same serializer. */
        private val AGENT_CONFIG =
            Yaml(configuration = YamlConfiguration(encodeDefaults = false))
                .encodeToString(K3sAgentConfig(server = AGENT_SERVER, token = AGENT_TOKEN, nodeLabel = listOf("role=db")))
    }

    /** What a script run left on the node. */
    private class NodeAfterRun(
        val exitCode: Int,
        val output: String,
        private val container: GenericContainer<*>,
    ) {
        fun sh(command: String): String = container.execInContainer("sh", "-c", command).stdout.trim()
    }

    /**
     * Starts a node, optionally with a mounted `/mnt/db1` and a seed command, and runs [script]
     * [runs] times (a node's script runs again on every `up`).
     */
    private fun runScript(
        script: String,
        mounted: Boolean,
        seed: String = "true",
        runs: Int = 1,
        extraArgs: String = "",
        agentConfig: String? = AGENT_CONFIG,
        check: (NodeAfterRun) -> Unit,
    ) {
        val container =
            GenericContainer(IMAGE)
                .withCommand("sleep", "600")
                .withCopyToContainer(Transferable.of(SYSTEMCTL_STUB, EXECUTABLE_MODE), "/usr/local/bin/systemctl")
                .withCopyToContainer(Transferable.of(INSTALLER_STUB, EXECUTABLE_MODE), "/usr/local/bin/install-k3s.sh")
                .withCopyFileToContainer(MountableFile.forClasspathResource("$RESOURCE_DIR/$script", EXECUTABLE_MODE), "/tmp/$script")
        if (script == AGENT && agentConfig != null) {
            container.withCopyToContainer(Transferable.of(agentConfig, CONFIG_MODE), AGENT_CONFIG_PATH)
        }
        if (mounted) container.withTmpFs(mapOf("/mnt/db1" to "rw"))

        container.use {
            it.start()
            // The AMI has k3s installed, so /var/lib/rancher exists before the script runs.
            assertThat(it.execInContainer("sh", "-c", "mkdir -p /var/lib/rancher && $seed").exitCode).describedAs("seed").isZero()
            val args = if (script == AGENT) extraArgs else ""
            var last = it.execInContainer("bash", "-c", "/tmp/$script$args")
            repeat(runs - 1) { _ -> last = it.execInContainer("bash", "-c", "/tmp/$script$args") }
            check(NodeAfterRun(last.exitCode, last.stdout + last.stderr, it))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [SERVER, AGENT])
    fun `container logs move to the data disk when the pod log directory does not exist yet`(script: String) {
        runScript(script, mounted = true) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("readlink /var/log/pods")).isEqualTo("/mnt/db1/container-logs")
            assertThat(node.sh("test -d /mnt/db1/container-logs && echo dir")).isEqualTo("dir")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [SERVER, AGENT])
    fun `logs already in the pod log directory are kept when it becomes a link`(script: String) {
        val seed = "mkdir -p /var/log/pods/default_pod_uid/app && echo line > /var/log/pods/default_pod_uid/app/0.log"
        runScript(script, mounted = true, seed = seed) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("readlink /var/log/pods")).isEqualTo("/mnt/db1/container-logs")
            assertThat(node.sh("cat /mnt/db1/container-logs/default_pod_uid/app/0.log")).isEqualTo("line")
            // The link was not made inside the old directory.
            assertThat(node.sh("ls -A /mnt/db1/container-logs")).isEqualTo("default_pod_uid")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [SERVER, AGENT])
    fun `running the script again leaves the relocation as it is`(script: String) {
        runScript(script, mounted = true, runs = 2) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("readlink /var/log/pods")).isEqualTo("/mnt/db1/container-logs")
            assertThat(node.sh("ls -A /mnt/db1/container-logs")).isEmpty()
        }
    }

    @Test
    fun `an agent without a data disk keeps its pod logs and the kubelet's default eviction`() {
        runScript(AGENT, mounted = false) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("test -L /var/log/pods && echo link || echo none")).isEqualTo("none")
            assertThat(node.sh("test -e $EVICTION_CONFIG && echo present || echo absent")).isEqualTo("absent")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [SERVER, AGENT])
    fun `a host whose data disk holds the database does not evict pods when that disk fills`(script: String) {
        runScript(script, mounted = true) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            val config = node.sh("cat $EVICTION_CONFIG")
            // The disk the database fills is the kubelet's nodefs and imagefs: no eviction on its space.
            assertThat(config).startsWith("kubelet-arg:").contains("eviction-hard=")
            val thresholds = config.substringAfter("eviction-hard=").substringBefore("\"").split(",")
            assertThat(thresholds).contains("nodefs.available<0%", "imagefs.available<0%")
            // Memory eviction and the inode guard keep their kubelet defaults.
            assertThat(thresholds).contains("memory.available<100Mi", "nodefs.inodesFree<5%")
        }
    }

    /**
     * The agent is started without waiting on systemd: registration is read from the K8s API, so
     * no SSH session is held while the agent waits on the server (#1134). A retry of the join
     * restarts the agent. A mode the script does not know fails it before systemd is touched.
     */
    @ParameterizedTest
    @CsvSource(
        "'', start --no-block k3s-agent",
        "' start', start --no-block k3s-agent",
        "' restart', restart --no-block k3s-agent",
    )
    fun `the agent start does not wait on systemd, and a retry restarts it`(
        mode: String,
        expected: String,
    ) {
        runScript(AGENT, mounted = false, extraArgs = mode) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            val agentCalls = "grep '^systemctl .*k3s-agent' $CALLS_LOG | grep -v list-unit-files | sed 's/^systemctl //'"
            assertThat(node.sh(agentCalls)).isEqualTo(expected)
        }
    }

    /**
     * The server and the node token come from the agent config `configure()` wrote (mode 0600), not
     * from the command line, which reaches failure messages and events. The token is never printed.
     */
    @Test
    fun `the agent installs with the server and token from its config and never prints the token`() {
        runScript(AGENT, mounted = false) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("grep ^installer $CALLS_LOG")).isEqualTo("installer url=$AGENT_SERVER token=$AGENT_TOKEN")
            assertThat(node.output).doesNotContain(AGENT_TOKEN)
        }
    }

    /** Without the agent config there is nothing to join with: the script fails before the installer or systemd runs. */
    @ParameterizedTest
    @ValueSource(strings = ["", "server: \"https://10.0.0.5:6443\"\n", "token: \"K10abc::server:def\"\n"])
    fun `an agent whose config lacks the server or the token fails before anything is installed`(config: String) {
        runScript(AGENT, mounted = false, agentConfig = config.ifEmpty { null }) { node ->
            assertThat(node.exitCode).isNotZero()
            assertThat(node.output).contains(AGENT_CONFIG_PATH)
            assertThat(node.sh("cat $CALLS_LOG 2>/dev/null || true")).isEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [" stop", " bogus"])
    fun `an unknown agent start mode fails before systemd is touched`(mode: String) {
        runScript(AGENT, mounted = false, extraArgs = mode) { node ->
            assertThat(node.exitCode).isNotZero()
            assertThat(node.sh("cat $CALLS_LOG 2>/dev/null || true")).isEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [SERVER, AGENT])
    fun `running the script again rewrites the same eviction settings`(script: String) {
        runScript(script, mounted = true, runs = 2) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("grep -c eviction-hard= $EVICTION_CONFIG")).isEqualTo("1")
        }
    }

    /**
     * The kubelet runs inside the k3s process and sets its open-file limit to max-open-files
     * (1000000 by default), below the unit's 1048576 that node tuning verifies on the running
     * process. The file appends to the eviction file's kubelet args, so neither replaces the other.
     */
    @ParameterizedTest
    @CsvSource("$SERVER, true", "$AGENT, true", "$AGENT, false")
    fun `the kubelet keeps the open-file limit the unit gives k3s`(
        script: String,
        mounted: Boolean,
    ) {
        runScript(script, mounted = mounted, runs = 2) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("cat $KUBELET_LIMITS_CONFIG"))
                .isEqualTo("kubelet-arg+:\n  - \"max-open-files=1048576\"")
        }
    }

    @Test
    fun `the server runs K3s on one-member embedded etcd with a larger quota`() {
        runScript(SERVER, mounted = true, runs = 2) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("grep -c '^cluster-init: true$' $DATASTORE_CONFIG")).isEqualTo("1")
            assertThat(node.sh("grep -c 'quota-backend-bytes=8589934592' $DATASTORE_CONFIG")).isEqualTo("1")
            // The collector's k3s-etcd job scrapes etcd's metrics listener on the control node.
            assertThat(node.sh("grep -c '^etcd-expose-metrics: true$' $DATASTORE_CONFIG")).isEqualTo("1")
            assertThat(node.sh("grep -c eviction-hard= $EVICTION_CONFIG")).isEqualTo("1")
        }
    }

    /** `up` applies a MutatingAdmissionPolicy, beta and off by default on K3s 1.35: only the server enables it. */
    @Test
    fun `the server enables the admission policy API and the agent does not`() {
        runScript(SERVER, mounted = true, runs = 2) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("cat $ADMISSION_POLICY_CONFIG"))
                .isEqualTo(
                    "kube-apiserver-arg+:\n  - \"feature-gates=MutatingAdmissionPolicy=true\"\n" +
                        "  - \"runtime-config=admissionregistration.k8s.io/v1beta1=true\"",
                )
        }
        runScript(AGENT, mounted = true) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("test -e $ADMISSION_POLICY_CONFIG && echo present || echo absent")).isEqualTo("absent")
        }
    }

    @Test
    fun `the server refuses to start K3s without a data disk and touches nothing`() {
        runScript(SERVER, mounted = false) { node ->
            assertThat(node.exitCode).isNotZero()
            assertThat(node.output).contains("/mnt/db1").containsIgnoringCase("datastore")
            assertThat(node.sh("test -L /var/lib/rancher/k3s && echo link || echo none")).isEqualTo("none")
            assertThat(node.sh("test -e $DATASTORE_CONFIG && echo present || echo absent")).isEqualTo("absent")
            assertThat(node.sh("cat $CALLS_LOG 2>/dev/null || true")).isEmpty()
        }
    }

    @Test
    fun `the agent writes no datastore setting`() {
        runScript(AGENT, mounted = true) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("test -e $DATASTORE_CONFIG && echo present || echo absent")).isEqualTo("absent")
            assertThat(node.sh("grep -rl 'cluster-init\\|etcd-arg\\|etcd-expose-metrics' /etc/rancher/k3s 2>/dev/null || true")).isEmpty()
        }
    }
}

private const val EXECUTABLE_MODE = 0b111_101_101
private const val CONFIG_MODE = 0b110_000_000
