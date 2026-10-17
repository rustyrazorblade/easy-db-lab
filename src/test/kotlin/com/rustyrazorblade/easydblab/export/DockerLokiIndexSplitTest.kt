package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.export.query.ComposeCli
import com.rustyrazorblade.easydblab.export.query.ComposeResult
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.LocalImages
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/**
 * The Loki index split as docker runs it (owner decision 22): Loki's own compactor over the local copy,
 * with no network, no credentials and retention and deletion off, as the operator; done once Loki says
 * its first pass over every table finished; and the project's logs saved and the project removed
 * whatever happened.
 */
class DockerLokiIndexSplitTest {
    @TempDir
    lateinit var work: File

    private val store get() = File(work, "store").apply { mkdirs() }
    private val project get() = File(work, "split")

    private val runs = mutableListOf<List<String>>()
    private var up = ComposeResult(0, "")

    /** What each `docker compose logs` of the service answers, in turn; the last one repeats. */
    private var logs = listOf("loki-1  | level=info msg=\"Loki started\"", "loki-1  | level=info msg=\"compactor started\"")
    private var running = "abc123"
    private val compose =
        ComposeCli { _, name, args, _ ->
            assertThat(name).isEqualTo("edl-export-loki-1")
            runs += args
            when {
                args.first() == "up" -> up
                args ==
                    listOf(
                        "logs",
                        "--no-color",
                        "loki",
                    )
                -> ComposeResult(0, logs[(runs.count { it == args } - 1).coerceAtMost(logs.lastIndex)])
                args.first() == "ps" -> ComposeResult(0, running)
                else -> ComposeResult(0, "")
            }
        }

    private fun split(timeout: Duration = Duration.ofSeconds(5)) =
        DockerLokiIndexSplit(
            compose,
            EventBus(),
            pollInterval = Duration.ofMillis(1),
            timeout = timeout,
            projectName = { "edl-export-loki-1" },
        ).split(store, project)

    private fun service() = ComposeFile.fromYaml(File(project, Constants.LocalStack.COMPOSE_FILE).readText()).services.getValue("loki")

    @Test
    fun `Loki's compactor runs with no network, no credentials, retention and deletion off, as the operator`() {
        split()

        val service = service()
        assertThat(service.image).isEqualTo(LocalImages.LOKI)
        assertThat(service.networkMode).isEqualTo("none")
        assertThat(service.user).matches("\\d+:\\d+")
        assertThat(service.command).contains(
            "-target=compactor",
            "-compactor.retention-enabled=false",
            "-compactor.deletion-mode=disabled",
            "-memberlist.bind-addr=127.0.0.1",
            "-memberlist.advertise-addr=127.0.0.1",
        )
        assertThat(service.environment.orEmpty().keys).noneMatch { it.startsWith("AWS_") && it != "AWS_REGION" }
        assertThat(service.volumes.orEmpty().map { it.source }).noneMatch { it.contains(".aws") }
        val storeMount = service.volumes.orEmpty().single { it.source == store.absolutePath }
        assertThat(storeMount.readOnly).isFalse()
    }

    @Test
    fun `the compactor reads the local copy as a filesystem store, never S3`() {
        split()

        val target =
            service()
                .volumes
                .orEmpty()
                .single { it.source == store.absolutePath }
                .target
        val config = File(project, "config/loki/loki.yaml").readText()
        assertThat(config).contains("object_store: filesystem").contains("directory: $target").doesNotContain("s3:")
    }

    @Test
    fun `the split waits until Loki's first pass over every table finished, then saves the logs and removes the project`() {
        logs = listOf("starting", "still waiting for the ring", "loki-1  | level=info msg=\"compactor started\"")

        split()

        assertThat(runs.count { it == listOf("logs", "--no-color", "loki") }).isEqualTo(3)
        assertThat(runs.first()).containsExactly("up", "-d")
        assertThat(runs.takeLast(2)).containsExactly(
            listOf("logs", "--no-color", "--timestamps"),
            listOf("down", "--remove-orphans", "-t", "60"),
        )
    }

    @Test
    fun `a compactor that exits before its first pass finished fails the split, naming its saved log, and the project is removed`() {
        logs = listOf("level=error msg=\"error running loki\"")
        running = ""

        assertThatThrownBy { split() }
            .hasMessageContaining("exited")
            .hasMessageContaining(File(project, Constants.Export.Query.STACK_LOG_FILE).path)
        assertThat(runs.last()).containsExactly("down", "--remove-orphans", "-t", "60")
    }

    @Test
    fun `a compactor that does not finish within the timeout fails the split, and the project is removed`() {
        logs = listOf("still compacting")

        assertThatThrownBy { split(timeout = Duration.ofMillis(50)) }.hasMessageContaining("PT0.05S")
        assertThat(runs.last()).containsExactly("down", "--remove-orphans", "-t", "60")
    }

    @Test
    fun `a project that does not start fails the split with compose's output, and the project is removed`() {
        up = ComposeResult(1, "no such image")

        assertThatThrownBy { split() }.hasMessageContaining("no such image")
        assertThat(runs.last()).containsExactly("down", "--remove-orphans", "-t", "60")
    }
}
