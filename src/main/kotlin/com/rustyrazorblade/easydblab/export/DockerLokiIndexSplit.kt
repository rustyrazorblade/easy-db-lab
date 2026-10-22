package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.export.query.ComposeCli
import com.rustyrazorblade.easydblab.export.query.JvmShutdownHooks
import com.rustyrazorblade.easydblab.export.query.ProjectRemoval
import com.rustyrazorblade.easydblab.export.query.ShutdownGuard
import com.rustyrazorblade.easydblab.export.query.ShutdownHooks
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.ComposeMount
import com.rustyrazorblade.easydblab.localstack.ComposeService
import com.rustyrazorblade.easydblab.localstack.ExportedFiles
import com.rustyrazorblade.easydblab.localstack.LocalImages
import com.rustyrazorblade.easydblab.localstack.LocalStackComposer
import com.rustyrazorblade.easydblab.localstack.LocalStackSpec
import com.rustyrazorblade.easydblab.localstack.backendEnvironment
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * [LokiIndexSplit] with Loki's own compactor (owner decision 22): one container of the stack's Loki
 * image, `-target=compactor`, over the local copy as a filesystem store (the cluster's `loki.yaml`
 * with the offline stack's filesystem storage, [ExportedFiles.lokiConfig], mounted read-write). It runs
 * with `network_mode: none`, no credentials and no environment beyond what the file expands, as the
 * operator (the owner of [split]'s project directory), retention off and deletion disabled, its
 * memberlist on loopback.
 *
 * The compactor runs once (`-compactor.run-once`): Loki 3.7.8 joins its ring, compacts every table
 * one time, logs [Constants.Export.LokiSplit.RUN_FAILED] if that failed, then always logs
 * [Constants.Export.LokiSplit.RUN_DONE], and waits for a signal; the process does not exit by itself.
 * The split looks at the log until the done line and fails when the failure line came with it, when
 * the container is no longer running first, or when [timeout] passes. Whatever happened, the
 * project's logs are saved in its directory ([ProjectRemoval]) and the project is removed; a project
 * compose cannot remove is reported.
 *
 * @property compose runs docker compose.
 * @property eventBus reports a project that cannot be removed.
 * @property pollInterval the gap between two looks at the log.
 * @property timeout how long the one compaction may take.
 * @property projectName the compose project's name, new for each split.
 * @property shutdownHooks where the hook that removes the project when the export is killed is registered while it runs.
 */
class DockerLokiIndexSplit(
    private val compose: ComposeCli,
    private val eventBus: EventBus,
    private val pollInterval: Duration = Duration.ofSeconds(Constants.Export.LokiSplit.POLL_SECONDS),
    private val timeout: Duration = Duration.ofMinutes(Constants.Export.LokiSplit.TIMEOUT_MINUTES),
    private val projectName: () -> String = { "edl-export-loki-${ExportWorkDirectory.newId()}" },
    private val shutdownHooks: ShutdownHooks = JvmShutdownHooks,
) : LokiIndexSplit {
    private companion object {
        val SERVICE = Constants.LocalStack.Services.LOKI
        const val CONFIG_DIR = "/etc/loki"
        const val STATE_DIR = "state"
        const val STATE = "/loki"

        /** What the configuration file expands where no bucket, region, tenant or cluster exists. */
        const val NONE = "export"
        val COMMAND_TIMEOUT: Duration = Duration.ofMinutes(Constants.Export.Query.COMPOSE_COMMAND_TIMEOUT_MINUTES)
    }

    override fun split(
        store: File,
        project: File,
    ) {
        File(project, STATE_DIR).mkdirs()
        files(store, ExportWorkDirectory.ownerOf(project)).forEach { (path, text) ->
            File(project, path).apply { parentFile.mkdirs() }.writeText(text)
        }
        val name = projectName()
        val removal = ProjectRemoval(compose, eventBus)
        // The same removal when the export is killed (Ctrl-C, SIGTERM) while the compactor runs.
        ShutdownGuard(shutdownHooks, "remove $name") {
            removal.saveLogs(project, name)
            removal.down(project, name)
        }.use { shutdown ->
            try {
                val up = compose.run(project, name, listOf("up", "-d"), COMMAND_TIMEOUT)
                check(up.exitCode == 0) { "docker compose could not start Loki's compactor (exit ${up.exitCode}):\n${up.output}" }
                awaitRun(project, name, removal.logFile(project))
            } finally {
                shutdown.run()
            }
        }
    }

    /** The project's files, by path relative to its directory: the compose file and Loki's configuration. */
    private fun files(
        store: File,
        owner: String,
    ): Map<String, String> {
        val clusterLoki = LocalStackComposer.resourceNextTo(LokiManifestBuilder::class.java, LokiManifestBuilder.CONFIG_FILE)
        val offline = ExportedFiles(LocalStackSpec.Mode.Offline(dataDir = NONE, home = NONE))
        val address = Constants.Export.LokiSplit.MEMBERLIST_ADDRESS
        val service =
            ComposeService(
                image = LocalImages.LOKI,
                user = owner,
                networkMode = "none",
                command =
                    listOf(
                        "-config.file=$CONFIG_DIR/${LokiManifestBuilder.CONFIG_FILE}",
                        "-config.expand-env=true",
                        "-target=compactor",
                        "-reporting.enabled=false",
                        "-compactor.run-once",
                        "-compactor.retention-enabled=false",
                        "-compactor.deletion-mode=disabled",
                        "-memberlist.bind-addr=$address",
                        "-memberlist.advertise-addr=$address",
                    ),
                environment = backendEnvironment(bucket = NONE, region = NONE, home = NONE),
                volumes =
                    listOf(
                        LocalStackComposer.configMount(SERVICE, CONFIG_DIR),
                        ComposeMount.bind(store.absolutePath, ExportedFiles.STORE, readOnly = false),
                        ComposeMount.bind("./$STATE_DIR", STATE, readOnly = false),
                    ),
                restart = "no",
            )
        return mapOf(
            Constants.LocalStack.COMPOSE_FILE to ComposeFile(mapOf(SERVICE to service)).toYaml(),
            LocalStackComposer.configPath(SERVICE, LokiManifestBuilder.CONFIG_FILE) to offline.lokiConfig(clusterLoki),
        )
    }

    /**
     * Looks at the compactor's log until its one compaction ended; fails when that compaction
     * reported an error, when the container stopped first, or at [timeout].
     */
    private fun awaitRun(
        project: File,
        name: String,
        logFile: File,
    ) {
        val deadline = Instant.now().plus(timeout)
        val attempts = (timeout.toMillis() / pollInterval.toMillis().coerceAtLeast(1)).toInt().coerceAtLeast(1) + 1
        val run =
            pollUntil("Loki index split", attempts, pollInterval, deadline, transient = { false }, done = { it != Run.RUNNING }) {
                look(project, name)
            }
        check(run != Run.FAILED) { "Loki's compactor reported an error splitting the index; its log is in ${logFile.path}" }
        check(run != Run.EXITED) { "Loki's compactor exited before it split the index; its log is in ${logFile.path}" }
        check(run == Run.DONE) { "Loki's compactor did not split the index within $timeout; its log is in ${logFile.path}" }
    }

    /** One look at the compactor: its compaction ended (well or not), it is no longer running, or it is still at work. */
    private fun look(
        project: File,
        name: String,
    ): Run {
        val logs = compose.run(project, name, listOf("logs", "--no-color", SERVICE), COMMAND_TIMEOUT).output
        if (logs.contains(Constants.Export.LokiSplit.RUN_DONE)) {
            return if (logs.contains(Constants.Export.LokiSplit.RUN_FAILED)) Run.FAILED else Run.DONE
        }
        val running = compose.run(project, name, listOf("ps", "--status", "running", "-q", SERVICE), COMMAND_TIMEOUT)
        return if (running.exitCode == 0 && running.output.isBlank()) Run.EXITED else Run.RUNNING
    }

    /** Where the compactor's one compaction stands at one look. */
    private enum class Run { RUNNING, DONE, FAILED, EXITED }
}
