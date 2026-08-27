package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.profiling.DesiredProfilingState
import com.rustyrazorblade.easydblab.profiling.ProfilingConfig
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [DefaultCassandraProfilingService] — the only place profiling talks to a node.
 *
 * `RemoteOperationsService` is mocked because these commands run over SSH on the control plane;
 * what is worth asserting is the exact command strings and the exact bytes uploaded.
 */
class CassandraProfilingServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService

    private val testHost =
        Host(
            public = "54.1.2.3",
            private = "10.0.0.1",
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    private val config =
        ProfilingConfig(
            enabled = true,
            asprofArgs = listOf("-e", "wall"),
            loopInterval = "30s",
            retentionMinutes = 30,
            maxBytes = 1024,
            pyroscopeUrl = "http://10.0.1.5:4040",
            clusterName = "test-cluster",
            updatedAt = "2026-08-24T10:15:30Z",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
            },
        )

    @BeforeEach
    fun setup() {
        mockRemoteOps = getKoin().get()
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response(""))
    }

    private fun makeService(): DefaultCassandraProfilingService = DefaultCassandraProfilingService(remoteOps = getKoin().get())

    private fun capturedCommands(): List<String> {
        val captor = argumentCaptor<String>()
        verify(mockRemoteOps, org.mockito.kotlin.atLeastOnce())
            .executeRemotely(eq(testHost), captor.capture(), any(), any())
        return captor.allValues
    }

    /**
     * Answers the node's two reads separately: `cat effective-state.json` and the chunk listing.
     * Listing behaviour depends on whether a session is live, so the two cannot share one stub.
     */
    private fun stubNode(
        running: Boolean,
        listing: String,
    ) {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).doAnswer { invocation ->
            val command = invocation.getArgument<String>(1)
            if (command.startsWith("cat ")) {
                Response("""{"running": $running, "pid": 4242}""")
            } else {
                Response(listing)
            }
        }
    }

    private fun listingCommand(): String = capturedCommands().first { it.contains("ls -1t") }

    @Test
    fun `writeDesiredState creates the directory, uploads, then moves atomically into place`() {
        makeService().writeDesiredState(testHost, config)

        val commands = capturedCommands()
        assertThat(commands.first()).contains("mkdir -p ${Constants.Profiling.DESIRED_STATE_DIR}")
        assertThat(commands.last())
            .describedAs("the final step must be a rename, so a partial write is never observable")
            .contains("mv ")
            .contains(Constants.Profiling.DESIRED_STATE_PATH)
    }

    @Test
    fun `writeDesiredState uploads the config as JSON preserving argument tokenization`() {
        // Read the payload at upload time: the service deletes its scratch file once it is sent.
        var uploadedBody = ""
        whenever(mockRemoteOps.upload(any(), any(), any())).doAnswer { invocation ->
            uploadedBody = Files.readString(invocation.getArgument<Path>(1))
        }

        makeService().writeDesiredState(testHost, config)

        assertThat(uploadedBody).contains("\"-e\"")
        assertThat(uploadedBody).contains("\"wall\"")
        assertThat(uploadedBody).contains("\"loopInterval\": \"30s\"")
    }

    @Test
    fun `writeDesiredState uploads exactly the keys the node's reconciler reads`() {
        // The other half of the cross-tier field contract. The reconciler pulls these out by name
        // with `yq -p=json '.enabled'` and friends; a field renamed here is silent on this side and
        // makes the node log config_unreadable on every pass — or, for pyroscopeUrl, POST every
        // chunk at a URL that cannot work. The mirror is asserted in
        // packer/cassandra/bin/edl-profiling-reconcile.test.sh.
        var uploadedBody = ""
        whenever(mockRemoteOps.upload(any(), any(), any())).doAnswer { invocation ->
            uploadedBody = Files.readString(invocation.getArgument<Path>(1))
        }

        makeService().writeDesiredState(testHost, config)

        val keys =
            Json
                .parseToJsonElement(uploadedBody)
                .jsonObject
                .keys
                .toList()
        assertThat(keys)
            .containsExactly(
                "enabled",
                "asprofArgs",
                "loopInterval",
                "retentionMinutes",
                "maxBytes",
                "pyroscopeUrl",
                "clusterName",
                "updatedAt",
            )
    }

    @Test
    fun `readDesiredState reads back the document the CLI last wrote`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doReturn(Response("""{"enabled": true, "asprofArgs": ["-e","cpu"], "retentionMinutes": 600}"""))

        val existing = makeService().readDesiredState(testHost)

        assertThat(existing).isInstanceOf(DesiredProfilingState.Configured::class.java)
        assertThat((existing as DesiredProfilingState.Configured).config.retentionMinutes).isEqualTo(600)
        assertThat(capturedCommands().first()).contains("cat ${Constants.Profiling.DESIRED_STATE_PATH}")
    }

    @Test
    fun `readDesiredState reports nothing rather than failing on an unconfigured node`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response(""))

        assertThat(makeService().readDesiredState(testHost)).isEqualTo(DesiredProfilingState.Unconfigured)
    }

    @Test
    fun `readDesiredState tells an unreadable document apart from an unconfigured node`() {
        // A document truncated by an interrupted write is not the same fact as a node nobody has
        // configured, and callers act on the difference: one falls back to defaults quietly, the
        // other has to say it is about to discard bounds the operator chose.
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doReturn(Response("""{"enabled": true, "asprofArgs": ["-e","cp"""))

        assertThat(makeService().readDesiredState(testHost))
            .isInstanceOf(DesiredProfilingState.Unreadable::class.java)
    }

    @Test
    fun `readEffectiveState parses what the reconciler wrote`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doReturn(Response("""{"running": true, "pid": 77, "args": ["-e","cpu"], "chunksPending": 4}"""))

        val state = makeService().readEffectiveState(testHost)

        assertThat(state?.pid).isEqualTo(77L)
        assertThat(state?.chunksPending).isEqualTo(4)
    }

    @Test
    fun `readEffectiveState reports unknown rather than failing when the node has no state`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response(""))

        assertThat(makeService().readEffectiveState(testHost)).isNull()
    }

    @Test
    fun `listCompletedChunks skips the chunk being written while a session is live`() {
        stubNode(running = true, listing = "/p/cassandra-1-300.jfr\n/p/cassandra-1-200.jfr\n")

        val chunks = makeService().listCompletedChunks(testHost, last = 2)

        assertThat(chunks).containsExactly("/p/cassandra-1-300.jfr", "/p/cassandra-1-200.jfr")
        val command = listingCommand()
        assertThat(command).contains("ls -1t ${Constants.Profiling.PROFILE_DIR}/*.jfr")
        assertThat(command).describedAs("the newest chunk is still being written").contains("tail -n +2")
        assertThat(command).contains("head -n 2")
    }

    @Test
    fun `listCompletedChunks offers the final chunk once the session has stopped`() {
        // Nothing is being written, so the newest chunk is the finalized last minute of the run.
        // Skipping it unconditionally would make that minute unreachable by fetch or flamegraph.
        stubNode(running = false, listing = "/p/cassandra-1-300.jfr\n")

        val chunks = makeService().listCompletedChunks(testHost, last = 5)

        assertThat(chunks).containsExactly("/p/cassandra-1-300.jfr")
        assertThat(listingCommand()).doesNotContain("tail -n +2")
    }

    @Test
    fun `listCompletedChunks offers chunks that have already shipped`() {
        // The reconciler marks a shipped chunk with a `-shipped.jfr` infix precisely so it keeps
        // answering to this glob; in steady state nearly every chunk on the node is a shipped one.
        stubNode(running = true, listing = "/p/cassandra-1-300.jfr\n/p/cassandra-1-200-shipped.jfr\n")

        val chunks = makeService().listCompletedChunks(testHost, last = 5)

        assertThat(chunks).contains("/p/cassandra-1-200-shipped.jfr")
        assertThat(listingCommand())
            .describedAs("filtering shipped chunks out would hide almost everything on the node")
            .doesNotContain("shipped")
    }

    @Test
    fun `listCompletedChunks returns nothing when the node has no completed chunks`() {
        stubNode(running = true, listing = "\n")

        assertThat(makeService().listCompletedChunks(testHost, last = 5)).isEmpty()
    }

    @Test
    fun `convertToFlamegraph passes every selected chunk to one conversion`() {
        val remotePath =
            makeService().convertToFlamegraph(
                host = testHost,
                chunks = listOf("/p/a.jfr", "/p/b.jfr"),
                format = "html",
                extraArgs = listOf("--threads"),
            )

        val command = capturedCommands().first()
        assertThat(command).contains(Constants.Profiling.JFRCONV_BIN)
        assertThat(command).contains("-o html")
        assertThat(command).contains("--threads")
        assertThat(command).contains("/p/a.jfr /p/b.jfr")
        assertThat(command).endsWith(remotePath)
        assertThat(remotePath).endsWith(".html")
    }

    @Test
    fun `convertToFlamegraph shell-quotes converter arguments`() {
        makeService().convertToFlamegraph(
            host = testHost,
            chunks = listOf("/p/a.jfr"),
            format = "html",
            extraArgs = listOf("--include", "org.apache cassandra"),
        )

        assertThat(capturedCommands().first()).contains("'org.apache cassandra'")
    }

    @Test
    fun `fetchChunks downloads each chunk into the destination directory`() {
        val destination = File(tempDir, "profiles/db0")

        makeService().fetchChunks(testHost, listOf("/p/a.jfr", "/p/b.jfr"), destination)

        val remote = argumentCaptor<String>()
        val local = argumentCaptor<Path>()
        verify(mockRemoteOps, org.mockito.kotlin.times(2))
            .download(eq(testHost), remote.capture(), local.capture())
        assertThat(remote.allValues).containsExactly("/p/a.jfr", "/p/b.jfr")
        assertThat(local.allValues.map { it.fileName.toString() }).containsExactly("a.jfr", "b.jfr")
        assertThat(destination).exists()
    }
}
