package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
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
        private const val SERVER = "start-k3s-server.sh"
        private const val AGENT = "start-k3s-agent.sh"

        /** systemctl reports no installed unit, so the script runs the (stubbed) installer. */
        private const val SYSTEMCTL_STUB = "#!/bin/sh\nexit 0\n"

        /** The k3s installer is not run; the script only has to reach it. */
        private const val INSTALLER_STUB = "#!/bin/sh\nexit 0\n"
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
        check: (NodeAfterRun) -> Unit,
    ) {
        val container =
            GenericContainer(IMAGE)
                .withCommand("sleep", "600")
                .withCopyToContainer(Transferable.of(SYSTEMCTL_STUB, EXECUTABLE_MODE), "/usr/local/bin/systemctl")
                .withCopyToContainer(Transferable.of(INSTALLER_STUB, EXECUTABLE_MODE), "/usr/local/bin/install-k3s.sh")
                .withCopyFileToContainer(MountableFile.forClasspathResource("$RESOURCE_DIR/$script", EXECUTABLE_MODE), "/tmp/$script")
        if (mounted) container.withTmpFs(mapOf("/mnt/db1" to "rw"))

        container.use {
            it.start()
            // The AMI has k3s installed, so /var/lib/rancher exists before the script runs.
            assertThat(it.execInContainer("sh", "-c", "mkdir -p /var/lib/rancher && $seed").exitCode).describedAs("seed").isZero()
            val args = if (script == AGENT) " https://10.0.0.5:6443 token" else ""
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

    @ParameterizedTest
    @ValueSource(strings = [SERVER, AGENT])
    fun `a host without a data disk keeps its pod logs and the kubelet's default eviction`(script: String) {
        runScript(script, mounted = false) { node ->
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

    @ParameterizedTest
    @ValueSource(strings = [SERVER, AGENT])
    fun `running the script again rewrites the same eviction settings`(script: String) {
        runScript(script, mounted = true, runs = 2) { node ->
            assertThat(node.exitCode).describedAs(node.output).isZero()
            assertThat(node.sh("grep -c eviction-hard= $EVICTION_CONFIG")).isEqualTo("1")
        }
    }
}

private const val EXECUTABLE_MODE = 0b111_101_101
