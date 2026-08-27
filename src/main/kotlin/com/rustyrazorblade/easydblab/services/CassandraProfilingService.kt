package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.profiling.DesiredProfilingState
import com.rustyrazorblade.easydblab.profiling.ProfilingConfig
import com.rustyrazorblade.easydblab.profiling.ProfilingEffectiveState
import com.rustyrazorblade.easydblab.profiling.parseProfilingConfig
import com.rustyrazorblade.easydblab.profiling.parseProfilingEffectiveState
import com.rustyrazorblade.easydblab.profiling.toJson
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.shellQuote
import java.io.File
import java.nio.file.Path

/**
 * Every SSH round-trip the profiling commands make.
 *
 * The commands themselves stay thin, per `commands/CLAUDE.md`: they parse and validate arguments and
 * delegate here, so all knowledge of node paths and remote command spelling lives in one place.
 *
 * Note the asymmetry between the paths. Starting profiling never builds a shell string — the
 * arguments travel inside a JSON document and are expanded as argv by the node's reconciler. Only
 * `fetch` and `flamegraph`, which invoke a remote tool directly, assemble a command line, and those
 * shell-quote every interpolated value.
 */
interface CassandraProfilingService {
    /**
     * Replaces a node's desired profiling state.
     *
     * The write is upload-then-rename so a reconcile pass can never observe a half-written
     * document — it sees either the complete previous one or the complete new one.
     */
    fun writeDesiredState(
        host: Host,
        config: ProfilingConfig,
    )

    /**
     * @return what the node's desired-state document holds: a config, nothing, or bytes that could
     *   not be decoded.
     *
     * Reading before writing is what lets a command change one field without silently resetting the
     * others to defaults the operator never chose — and the three-way answer is what lets it say so
     * when it cannot.
     */
    fun readDesiredState(host: Host): DesiredProfilingState

    /** @return the node's last-written effective state, or null if it is absent or unreadable. */
    fun readEffectiveState(host: Host): ProfilingEffectiveState?

    /**
     * Lists the node's completed JFR chunks, newest first — shipped ones included.
     *
     * Excludes the newest file only while a session is live, because that is the one still being
     * written and has no finalized constant pool. Once the session stops there is nothing writing
     * and the newest file is the final chunk of the run, which must stay retrievable.
     *
     * @param last maximum number of chunks to return.
     */
    fun listCompletedChunks(
        host: Host,
        last: Int,
    ): List<String>

    /** Downloads the named chunks into [destination], creating it if needed. */
    fun fetchChunks(
        host: Host,
        chunks: List<String>,
        destination: File,
    )

    /**
     * Converts chunks into a flame graph on the node — `jfrconv` lives there and not on the
     * developer's machine, and the rendered output is far smaller than the raw JFR.
     *
     * All chunks go into one conversion, because a single one-minute chunk is rarely enough to read.
     *
     * @return the remote path of the rendered file.
     */
    fun convertToFlamegraph(
        host: Host,
        chunks: List<String>,
        format: String,
        extraArgs: List<String>,
    ): String

    /** Downloads a single remote file. */
    fun download(
        host: Host,
        remotePath: String,
        local: Path,
    )
}

/**
 * Default [CassandraProfilingService], driving the node over SSH.
 *
 * @property remoteOps the SSH transport; profiling never shells out locally, because none of the
 *   cluster tooling it drives is installed on the developer's machine.
 */
class DefaultCassandraProfilingService(
    private val remoteOps: RemoteOperationsService,
) : CassandraProfilingService {
    override fun writeDesiredState(
        host: Host,
        config: ProfilingConfig,
    ) {
        val scratch = File.createTempFile("profiling", ".json")
        try {
            scratch.writeText(config.toJson())
            remoteOps.executeRemotely(host, "sudo mkdir -p ${Constants.Profiling.DESIRED_STATE_DIR}")
            remoteOps.upload(host, scratch.toPath(), STAGED_CONFIG_NAME)
            remoteOps.executeRemotely(host, "sudo chmod 644 $STAGED_CONFIG_NAME")
            // rename(2) within one filesystem: the reconciler never sees a partial document.
            remoteOps.executeRemotely(host, "sudo mv $STAGED_CONFIG_NAME ${Constants.Profiling.DESIRED_STATE_PATH}")
        } finally {
            scratch.delete()
        }
    }

    override fun readDesiredState(host: Host): DesiredProfilingState {
        val path = Constants.Profiling.DESIRED_STATE_PATH
        val document = readNodeDocument(host, path)
        return when {
            document.isBlank() -> DesiredProfilingState.Unconfigured
            else ->
                parseProfilingConfig(document, source = "${host.alias}:$path")
                    ?.let { DesiredProfilingState.Configured(it) }
                    ?: DesiredProfilingState.Unreadable(document)
        }
    }

    override fun readEffectiveState(host: Host): ProfilingEffectiveState? =
        parseProfilingEffectiveState(
            readNodeDocument(host, Constants.Profiling.EFFECTIVE_STATE_PATH),
            source = "${host.alias}:${Constants.Profiling.EFFECTIVE_STATE_PATH}",
        )

    /**
     * Reads one of the node's JSON documents, treating an absent file as empty content.
     *
     * Absence is an ordinary state — a node that has never been configured, or one whose reconciler
     * has not completed a pass — so it is reported, not raised.
     */
    private fun readNodeDocument(
        host: Host,
        path: String,
    ): String = remoteOps.executeRemotely(host, "cat $path 2>/dev/null || true", output = false).text

    override fun listCompletedChunks(
        host: Host,
        last: Int,
    ): List<String> {
        // Mirrors the reconciler's own rule: only a live session has a chunk open for writing.
        val skipInFlight = if (readEffectiveState(host)?.running == true) "| tail -n +2 " else ""
        val response =
            remoteOps.executeRemotely(
                host,
                "ls -1t ${Constants.Profiling.PROFILE_DIR}/*.jfr 2>/dev/null $skipInFlight| head -n $last || true",
                output = false,
            )
        return response.text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    override fun fetchChunks(
        host: Host,
        chunks: List<String>,
        destination: File,
    ) {
        destination.mkdirs()
        chunks.forEach { chunk ->
            remoteOps.download(host, chunk, File(destination, File(chunk).name).toPath())
        }
    }

    override fun convertToFlamegraph(
        host: Host,
        chunks: List<String>,
        format: String,
        extraArgs: List<String>,
    ): String {
        val remotePath = "${Constants.Profiling.ARTIFACTS_DIR}/flame-${host.alias}-${System.currentTimeMillis()}.$format"
        val arguments =
            buildList {
                add(Constants.Profiling.JFRCONV_BIN)
                add("-o")
                add(format)
                addAll(extraArgs)
                addAll(chunks)
                add(remotePath)
            }
        remoteOps.executeRemotely(host, arguments.joinToString(" ") { it.shellQuote() })
        return remotePath
    }

    override fun download(
        host: Host,
        remotePath: String,
        local: Path,
    ) {
        remoteOps.download(host, remotePath, local)
    }

    private companion object {
        /** Staging name in the SSH user's home directory, renamed into place under sudo. */
        const val STAGED_CONFIG_NAME = "profiling.json"
    }
}
