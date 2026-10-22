package com.rustyrazorblade.easydblab.export.query

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.LocalImages
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The metrics rewrite as thanos runs it (spike S4): the pinned image, a filesystem object store
 * rooted at the staged tenant directory, delete matchers for every other cluster, its scratch
 * outside the tenant directory, and nothing else: no network, no environment, no credentials, and
 * the operator's uid:gid so it can write the staging directory.
 */
class ThanosBlockRewriteTest {
    @TempDir
    lateinit var work: File

    private val runs = mutableListOf<Triple<File, String, List<String>>>()
    private var exitCode = 0
    private var runResult: ComposeResult? = null
    private var killResult = ComposeResult(0, "")
    private var downResult = ComposeResult(0, "")
    private var stillThere = ""

    /** The JVM's shutdown hooks; [killDuringRun] runs them while thanos runs, as a SIGTERM would. */
    private val hooks = mutableListOf<Thread>()
    private var killDuringRun = false
    private val shutdownHooks =
        object : ShutdownHooks {
            override fun add(hook: Thread) {
                hooks += hook
            }

            override fun remove(hook: Thread) {
                hooks -= hook
            }
        }
    private val compose =
        ComposeCli { directory, project, args, _ ->
            runs += Triple(directory, project, args)
            when (args.first()) {
                "run" -> {
                    if (killDuringRun) hooks.toList().forEach { it.run() }
                    runResult ?: ComposeResult(exitCode, "thanos output")
                }
                "kill" -> killResult
                "down" -> downResult
                "ps" -> ComposeResult(0, stillThere)
                else -> ComposeResult(0, "")
            }
        }
    private val events = mutableListOf<Event>()
    private val eventBus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    private val staging get() = File(work, "metrics")
    private val tenantDir get() = File(staging, "mimir/acme").apply { mkdirs() }
    private val tmpDir get() = File(staging, ".thanos-tmp-acme")

    private fun rewrite() =
        ThanosBlockRewrite(
            compose,
            projects = File(work, "projects"),
            project = "edl-export-1",
            owner = "501:20",
            eventBus = eventBus,
            shutdownHooks = shutdownHooks,
        ).rewrite(tenantDir, tmpDir, listOf("01A", "01B"), listOf("x-1", "y-2"))

    /** A killed export left thanos's one-off container running, writing into the staging directory. */
    @Test
    fun `an export killed while thanos runs kills and removes thanos's project, and a finished rewrite leaves no hook`() {
        killDuringRun = true

        rewrite()

        val project = "edl-export-1-rewrite-acme"
        assertThat(runs.map { listOf(it.second) + it.third }).containsSubsequence(
            listOf(project, "run", "--rm", "thanos"),
            listOf(project, "kill"),
            listOf(project, "down", "--remove-orphans", "-t", "60"),
        )
        assertThat(hooks).isEmpty()
    }

    private val timedOut = ComposeResult(-1, "docker compose run --rm thanos did not finish within PT6H", timedOut = true)

    private fun projectDir() = runs.first().first

    private fun service() =
        ComposeFile.fromYaml(File(projectDir(), Constants.LocalStack.COMPOSE_FILE).readText()).services.getValue("thanos")

    @Test
    fun `thanos runs once, as a one-off run of its project, then the project is removed`() {
        rewrite()

        assertThat(runs.map { it.third }).containsExactly(listOf("run", "--rm", "thanos"), listOf("down", "--remove-orphans", "-t", "60"))
        assertThat(runs.map { it.second }).containsOnly("edl-export-1-rewrite-acme")
    }

    @Test
    fun `thanos has no network, no environment and no credentials, and runs as the operator`() {
        rewrite()

        val service = service()
        assertThat(service.image).isEqualTo(LocalImages.THANOS)
        assertThat(service.networkMode).isEqualTo("none")
        assertThat(service.environment).isNull()
        assertThat(service.user).isEqualTo("501:20")
        assertThat(service.volumes.orEmpty().map { it.source }).noneMatch { it.contains(".aws") }
    }

    @Test
    fun `the rewrite keeps the source blocks, names each one, and writes Prometheus blocks`() {
        rewrite()

        assertThat(service().command).containsExactly(
            "tools",
            "bucket",
            "rewrite",
            "--objstore.config-file=/etc/thanos/objstore.yaml",
            "--rewrite.to-delete-config-file=/etc/thanos/delete.yaml",
            "--tmp.dir=/staging/.thanos-tmp-acme",
            "--prom-blocks",
            "--no-dry-run",
            "--id=01A",
            "--id=01B",
        )
        assertThat(service().command).noneMatch { it.startsWith("--delete-blocks") || it.startsWith("--hash-func") }
        assertThat(
            service()
                .volumes
                .orEmpty()
                .single { it.target == "/staging" }
                .source,
        ).isEqualTo(staging.absolutePath)
    }

    @Test
    fun `the object store is the staged tenant directory, and every other cluster's series is deleted`() {
        rewrite()

        val objstore = Yaml.default.parseToYamlNode(File(projectDir(), "config/objstore.yaml").readText()) as YamlMap
        assertThat(objstore.getScalar("type")?.content).isEqualTo("FILESYSTEM")
        assertThat(objstore.get<YamlMap>("config")?.getScalar("directory")?.content).isEqualTo("/staging/mimir/acme")
        val deletions = Yaml.default.parseToYamlNode(File(projectDir(), "config/delete.yaml").readText()) as YamlList
        assertThat((deletions.items.single() as YamlMap).getScalar("matchers")?.content).isEqualTo("{cluster!~\"x-1|y-2\"}")
    }

    @Test
    fun `a failed rewrite fails with thanos's output`() {
        exitCode = 1

        assertThatThrownBy { rewrite() }.hasMessageContaining("thanos output")
    }

    @Test
    fun `a scratch directory inside the tenant directory is refused`() {
        assertThatThrownBy {
            ThanosBlockRewrite(
                compose,
                File(work, "p"),
                "p",
                "1:1",
                eventBus,
            ).rewrite(tenantDir, File(tenantDir, "tmp"), listOf("01A"), listOf("x"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    /** At the timeout only the CLI is killed; thanos would keep writing into the staging directory. */
    @Test
    fun `a rewrite past its timeout kills thanos's container, confirms it is gone, and fails`() {
        runResult = timedOut

        assertThatThrownBy { rewrite() }
            .hasMessageContaining("did not finish within")
            .hasMessageContaining("stopped")
        assertThat(runs.map { it.third }).containsExactly(
            listOf("run", "--rm", "thanos"),
            listOf("kill"),
            listOf("down", "--remove-orphans", "-t", "60"),
            listOf("ps", "-a", "-q"),
        )
    }

    @Test
    fun `a timed-out rewrite whose container is still listed fails saying it may still be running, with the commands that stop it`() {
        runResult = timedOut
        stillThere = "4438514\n"

        assertThatThrownBy { rewrite() }
            .hasMessageContaining("may still be running")
            .hasMessageContaining("4438514")
            .hasMessageContaining("docker compose -p edl-export-1-rewrite-acme kill")
    }

    @Test
    fun `a timed-out rewrite whose kill fails is not taken as stopped`() {
        runResult = timedOut
        killResult = ComposeResult(1, "Cannot connect to the Docker daemon")

        assertThatThrownBy { rewrite() }.hasMessageContaining("may still be running").hasMessageContaining("Cannot connect")
    }

    @Test
    fun `a rewrite project compose cannot remove is reported, and the rewrite still succeeds`() {
        downResult = ComposeResult(1, "network in use")

        rewrite()

        val left = events.filterIsInstance<Event.Export.StackNotRemoved>().single()
        assertThat(left.project).isEqualTo("edl-export-1-rewrite-acme")
        assertThat(left.output).isEqualTo("network in use")
    }
}
