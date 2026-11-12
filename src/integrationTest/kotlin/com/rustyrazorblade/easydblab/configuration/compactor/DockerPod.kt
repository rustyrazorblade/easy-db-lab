package com.rustyrazorblade.easydblab.configuration.compactor

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Volume
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.PodSpec
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.testcontainers.DockerClientFactory
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.LogUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Runs a Fabric8 pod spec's containers in Docker as the kubelet would: one network namespace, the
 * init containers first and to completion, each container's own image, command, arguments,
 * environment and user, and its volumes.
 *
 * - An emptyDir is a fresh Docker volume shared by the containers that mount it.
 * - The ConfigMap volume is copied into each container that mounts it, from [configFiles].
 * - A `File` host path is bound from [hostFiles]; a `Directory` host path is a fresh Docker volume,
 *   given to [hostDirectoryOwners] the way `CompactorAgentInstaller` does it on a node.
 * - A ConfigMap key reference reads [clusterConfig]; the `metadata.name` field reads [name].
 *
 * Containers are started with the Docker client, not Testcontainers, because a container that shares
 * another's network namespace cannot join Testcontainers' host-access network. Everything is labelled
 * with the Testcontainers session, so it is reaped with it.
 */
class DockerPod(
    private val name: String,
    private val spec: PodSpec,
    private val configFiles: Map<String, String>,
    private val clusterConfig: Map<String, String>,
    private val hostFiles: Map<String, Path> = emptyMap(),
    private val hostDirectoryOwners: Map<String, Long> = emptyMap(),
    private val extraEnv: Map<String, String> = emptyMap(),
    private val extraArgs: Map<String, List<String>> = emptyMap(),
) : AutoCloseable {
    private companion object {
        const val SANDBOX_IMAGE = "busybox:1.37.0-musl"
        val INIT_TIMEOUT: Duration = Duration.ofMinutes(2)
        const val LOG_TAIL = 6000
    }

    private val docker = DockerClientFactory.instance().client()
    private val volumesByName = spec.volumes.associateBy { it.name }
    private val volumes = mutableMapOf<String, String>()
    private val started = mutableMapOf<String, String>()
    private lateinit var sandbox: String

    private val labels =
        DockerClientFactory.DEFAULT_LABELS + (DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL to DockerClientFactory.SESSION_ID)

    /** The sandbox's address on the Docker network, where the containers' ports are. */
    fun address(): String =
        checkNotNull(
            docker
                .inspectContainerCmd(sandbox)
                .exec()
                .networkSettings.networks.values
                .first()
                .ipAddress,
        ) { "the pod sandbox has no address" }

    /** Starts the sandbox, runs the init containers to completion, then starts every container. */
    fun start(): DockerPod {
        pull(SANDBOX_IMAGE)
        sandbox =
            docker
                .createContainerCmd(SANDBOX_IMAGE)
                .withCmd("/bin/busybox", "sleep", "86400")
                .withLabels(labels)
                .exec()
                .id
        docker.startContainerCmd(sandbox).exec()
        spec.volumes.filter { it.emptyDir != null || it.hostPath?.type == "Directory" }.forEach { volume ->
            volumes[volume.name] =
                "pod-$name-${volume.name}-${UUID.randomUUID().toString().take(6)}".also {
                    docker
                        .createVolumeCmd()
                        .withName(it)
                        .withLabels(labels)
                        .exec()
                }
            hostDirectoryOwners[volume.name]?.let { owner -> chown(volume.name, owner) }
        }
        spec.initContainers.forEach { init ->
            val id = run(init)
            val exit = docker.waitContainerCmd(id).start().awaitStatusCode(INIT_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
            check(exit == 0) { "init container ${init.name} exited $exit: ${logs(init.name)}" }
        }
        spec.containers.forEach { run(it) }
        return this
    }

    private fun pull(image: String) {
        RemoteDockerImage(DockerImageName.parse(image)).get()
    }

    /** Gives a host-directory volume to [owner], as the installer's chown does on the node. */
    private fun chown(
        volume: String,
        owner: Long,
    ) {
        val id =
            docker
                .createContainerCmd(SANDBOX_IMAGE)
                .withCmd("/bin/busybox", "chown", "-R", "$owner:$owner", "/v")
                .withLabels(labels)
                .withHostConfig(HostConfig.newHostConfig().withBinds(Bind(volumes.getValue(volume), Volume("/v"))))
                .exec()
                .id
        docker.startContainerCmd(id).exec()
        docker.waitContainerCmd(id).start().awaitStatusCode()
        docker.removeContainerCmd(id).exec()
    }

    private fun envValue(env: EnvVar): String =
        when {
            env.value != null -> env.value
            env.valueFrom?.configMapKeyRef != null -> clusterConfig.getValue(env.valueFrom.configMapKeyRef.key)
            env.valueFrom?.fieldRef?.fieldPath == "metadata.name" -> name
            else -> error("Unsupported env var ${env.name}")
        }

    private fun binds(container: Container): List<Bind> =
        container.volumeMounts.mapNotNull { mount ->
            val volume = volumesByName.getValue(mount.name)
            when {
                volume.configMap != null -> null
                volume.hostPath?.type == "File" -> Bind(hostFiles.getValue(volume.name).toString(), Volume(mount.mountPath))
                else -> Bind(volumes.getValue(volume.name), Volume(mount.mountPath))
            }
        }

    private fun run(container: Container): String {
        pull(container.image)
        val env = container.env.associate { it.name to envValue(it) } + extraEnv
        val id =
            docker
                .createContainerCmd(container.image)
                .withName("pod-$name-${container.name}-${UUID.randomUUID().toString().take(6)}")
                .apply { if (container.command.isNotEmpty()) withEntrypoint(container.command) }
                .withCmd(container.args.orEmpty() + extraArgs[container.name].orEmpty())
                .withEnv(env.map { (key, value) -> "$key=$value" })
                .withLabels(labels)
                .withHostConfig(
                    HostConfig
                        .newHostConfig()
                        .withNetworkMode("container:$sandbox")
                        .withBinds(binds(container)),
                ).exec()
                .id
        started[container.name] = id
        container.volumeMounts
            .firstOrNull { mount -> volumesByName.getValue(mount.name).configMap != null }
            ?.let { copyConfig(id, it.mountPath) }
        docker.startContainerCmd(id).exec()
        return id
    }

    private fun copyConfig(
        id: String,
        mountPath: String,
    ) {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { out ->
            configFiles.forEach { (file, content) -> Transferable.of(content).transferTo(out, "${mountPath.removePrefix("/")}/$file") }
        }
        docker
            .copyArchiveToContainerCmd(id)
            .withTarInputStream(ByteArrayInputStream(tar.toByteArray()))
            .withRemotePath("/")
            .exec()
    }

    /** The last lines [container] wrote. */
    fun logs(container: String): String = started[container]?.let { LogUtils.getOutput(docker, it).takeLast(LOG_TAIL) }.orEmpty()

    /** Whether [container] is running. */
    fun isRunning(container: String): Boolean =
        docker
            .inspectContainerCmd(started.getValue(container))
            .exec()
            .state.running == true

    /** Whether [path] exists in [container]. */
    fun exists(
        container: String,
        path: String,
    ): Boolean = execExitCode(docker, started.getValue(container), listOf("test", "-e", path)) == 0L

    /**
     * Whether `http://127.0.0.1:[port][path]` in the pod answers 200 within [timeoutSeconds]. The
     * request runs in the sandbox, whose network namespace every container shares, so it reaches a
     * container's port as the kubelet's probe would, on any Docker host.
     */
    fun answers200(
        port: Int,
        path: String,
        timeoutSeconds: Int = 5,
    ): Boolean =
        execExitCode(
            docker,
            sandbox,
            listOf("/bin/busybox", "wget", "-q", "-T", "$timeoutSeconds", "-O", "/dev/null", "http://127.0.0.1:$port$path"),
        ) == 0L

    /** Stops the whole pod at once, as a node that goes away does: no SIGTERM grace, no lease release. */
    fun kill() {
        (started.values + sandbox).forEach { runCatching { docker.killContainerCmd(it).exec() } }
    }

    override fun close() {
        (started.values + sandbox).forEach { runCatching { docker.removeContainerCmd(it).withForce(true).exec() } }
        volumes.values.forEach { runCatching { docker.removeVolumeCmd(it).exec() } }
    }
}
