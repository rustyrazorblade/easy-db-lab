package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.export.query.ComposeCli
import com.rustyrazorblade.easydblab.export.query.ProjectRemoval
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.ComposeMount
import com.rustyrazorblade.easydblab.localstack.ComposeService
import com.rustyrazorblade.easydblab.localstack.ExportedFiles
import com.rustyrazorblade.easydblab.localstack.LocalImages
import com.rustyrazorblade.easydblab.localstack.LocalStackComposer
import com.rustyrazorblade.easydblab.localstack.LocalStackSpec
import com.rustyrazorblade.easydblab.localstack.backendEnvironment
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
 * Loki 3.7.8 has no run-once mode: the compactor waits one compaction interval, passes over every
 * table, logs [Constants.Export.LokiSplit.FIRST_PASS_DONE], and then waits for the next interval. The
 * split looks at the log until that line, failing when the container is no longer running first or
 * when [timeout] passes. Whatever happened, the project's logs are saved in its directory
 * ([ProjectRemoval]) and the project is removed; a project compose cannot remove is reported.
 *
 * @property compose runs docker compose.
 * @property eventBus reports a project that cannot be removed.
 * @property pollInterval the gap between two looks at the log.
 * @property timeout how long the first pass may take.
 * @property projectName the compose project's name, new for each split.
 */
class DockerLokiIndexSplit(
    private val compose: ComposeCli,
    private val eventBus: EventBus,
    private val pollInterval: Duration = Duration.ofSeconds(Constants.Export.LokiSplit.POLL_SECONDS),
    private val timeout: Duration = Duration.ofMinutes(Constants.Export.LokiSplit.TIMEOUT_MINUTES),
    private val projectName: () -> String = { "edl-export-loki-${ExportWorkDirectory.newId()}" },
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
        try {
            val up = compose.run(project, name, listOf("up", "-d"), COMMAND_TIMEOUT)
            check(up.exitCode == 0) { "docker compose could not start Loki's compactor (exit ${up.exitCode}):\n${up.output}" }
            awaitFirstPass(project, name, removal.logFile(project))
        } finally {
            removal.saveLogs(project, name)
            removal.down(project, name)
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
                        "-compactor.compaction-interval=${Constants.Export.LokiSplit.COMPACTION_INTERVAL_SECONDS}s",
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

    /** Looks at the compactor's log until its first pass finished; fails when it stopped first or at [timeout]. */
    private fun awaitFirstPass(
        project: File,
        name: String,
        logFile: File,
    ) {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val logs = compose.run(project, name, listOf("logs", "--no-color", SERVICE), COMMAND_TIMEOUT)
            if (logs.output.contains(Constants.Export.LokiSplit.FIRST_PASS_DONE)) return
            val running = compose.run(project, name, listOf("ps", "--status", "running", "-q", SERVICE), COMMAND_TIMEOUT)
            check(running.exitCode != 0 || running.output.isNotBlank()) {
                "Loki's compactor exited before it split the index; its log is in ${logFile.path}"
            }
            Thread.sleep(pollInterval.toMillis())
        }
        error("Loki's compactor did not split the index within $timeout; its log is in ${logFile.path}")
    }
}
