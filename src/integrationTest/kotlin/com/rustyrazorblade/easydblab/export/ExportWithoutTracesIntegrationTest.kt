package com.rustyrazorblade.easydblab.export

import com.charleskorn.kaml.Yaml
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.localstack.ComposeProject
import com.rustyrazorblade.easydblab.localstack.PackagedDashboardTree
import com.rustyrazorblade.easydblab.localstack.PackagedKitDashboards
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.zip.ZipFile

/**
 * An export with no traces opens with a running Tempo. The data directory is mounted read-only, and
 * the offline Tempo creates its local backend's directory, `data/tempo/`, at start: when the zip had
 * no traces, and so no `data/tempo/`, Tempo failed with `mkdir /store/tempo: read-only file system`
 * and restarted forever. The zip now always holds the directory.
 *
 * The zip is the real stack an export writes ([ExportStackFiles.put] through [ExportZipWriter]) with
 * no data at all, unpacked as `unzip` unpacks it, and opened with `docker compose up` on an internal
 * network, as an operator opens it.
 */
class ExportWithoutTracesIntegrationTest {
    private companion object {
        const val TENANT = "acme"
        const val ROOT = "no-traces"
        val READY_WAIT: Duration = Duration.ofMinutes(3)
        val POLL: Duration = Duration.ofSeconds(3)
        const val PROBE_TIMEOUT_SECONDS = "5"
    }

    @TempDir
    lateinit var root: File

    private val suffix = UUID.randomUUID().toString().take(8)
    private lateinit var stack: File
    private lateinit var project: ComposeProject

    @AfterEach
    fun cleanUp() {
        if (::project.isInitialized) project.compose("down", "-v", "--remove-orphans", "--timeout", "30")
        if (::stack.isInitialized) removeRootOwnedState()
    }

    @Serializable
    private data class InternalNetwork(
        val internal: Boolean,
    )

    @Serializable
    private data class NetworkOverride(
        val networks: Map<String, InternalNetwork>,
    )

    @Test
    fun `an export with no traces opens with a Tempo that answers ready and does not restart`() {
        val zip = File(root, "$ROOT.zip")
        val stackFiles =
            ExportStackFiles(PackagedDashboardTree(GrafanaDashboardCatalog.discover(), PackagedKitDashboards.discover()), toolVersion = "it")
        ExportZipWriter().write(ExportTarget.of(zip)) { sink -> stackFiles.put(ExportRecorder(sink), TENANT, root = ROOT) }

        stack = unzip(zip)
        assertThat(File(stack, "${Constants.Export.DATA_DIR}/${Constants.Observability.TRACES_ROOT}")).isDirectory().isEmptyDirectory()
        val override = NetworkOverride(mapOf("default" to InternalNetwork(internal = true)))
        File(stack, Constants.LocalStack.OVERRIDE_FILE).writeText(Yaml.default.encodeToString(NetworkOverride.serializer(), override))
        project = ComposeProject(stack, "edl-export-notraces-$suffix")
        project.composeOrFail("up", "-d", Constants.LocalStack.Services.TEMPO)

        val deadline = System.nanoTime() + READY_WAIT.toNanos()
        while (!tempoReady() && System.nanoTime() < deadline) Thread.sleep(POLL.toMillis())
        val logs = project.logs(Constants.LocalStack.Services.TEMPO)
        assertThat(tempoReady()).describedAs { "Tempo answers /ready within $READY_WAIT\n${logs.takeLast(4000)}" }.isTrue()
        assertThat(logs).doesNotContain("read-only file system")
        assertThat(project.inspect(Constants.LocalStack.Services.TEMPO, "{{.RestartCount}}")).isEqualTo("0")
    }

    /** Unpacks [zip] beside it as `unzip` does, directory entries included; returns the stack directory. */
    private fun unzip(zip: File): File {
        val parent = File(root, "opened").apply { mkdirs() }
        ZipFile(zip).use { file ->
            file.entries().asSequence().forEach { entry ->
                val target = File(parent, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    file.getInputStream(entry).use { input -> target.writeBytes(input.readBytes()) }
                }
            }
        }
        return File(parent, ROOT)
    }

    /** Whether Tempo answers `/ready`, asked from inside its own network namespace by a one-off busybox. */
    private fun tempoReady(): Boolean =
        project
            .docker(
                "run",
                "--rm",
                "--network",
                "container:${project.containerId(Constants.LocalStack.Services.TEMPO)}",
                Constants.LocalStack.HEALTHCHECK_TOOLS_IMAGE,
                "wget",
                "-q",
                "-O",
                "-",
                "-T",
                PROBE_TIMEOUT_SECONDS,
                "http://127.0.0.1:${Constants.K8s.TEMPO_PORT}/ready",
            ).let { it.exitCode == 0 && it.output.trim() == "ready" }

    /** Tempo runs as root, so a container removes the state it wrote. */
    private fun removeRootOwnedState() {
        GenericContainer(Constants.LocalStack.HEALTHCHECK_TOOLS_IMAGE)
            .withCreateContainerCmdModifier { it.hostConfig?.withBinds(Bind(stack.absolutePath, Volume("/stack"))) }
            .withCommand("rm", "-rf", "/stack/${Constants.LocalStack.CACHE_DIR}", "/stack/${Constants.LocalStack.GRAFANA_DATA_DIR}")
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))
            .use { it.start() }
    }
}
