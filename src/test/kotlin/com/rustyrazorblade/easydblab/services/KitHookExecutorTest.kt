package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class KitHookExecutorTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val capturedEvents: MutableList<Event> = mutableListOf()
    private lateinit var eventBus: EventBus
    private lateinit var executor: KitHookExecutor
    private lateinit var workingDir: File

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts =
                mapOf(
                    ServerType.Control to
                        listOf(
                            ClusterHost(
                                publicIp = "54.1.2.3",
                                privateIp = "10.0.0.1",
                                alias = "control0",
                                availabilityZone = "us-west-2a",
                                instanceId = "i-ctrl",
                            ),
                        ),
                ),
            runningKits = setOf("presto"),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        workingDir = get<Context>().workingDirectory
        capturedEvents.clear()
        eventBus = get()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        executor =
            DefaultKitHookExecutor(
                context = get(),
                clusterStateManager = mockClusterStateManager,
                eventBus = eventBus,
                maxAttempts = 1,
                backoffBaseMs = 0L,
            )
    }

    private fun writeKit(
        name: String,
        yaml: String,
        hookScript: String = "",
    ): File {
        val kitDir = File(workingDir, name).also { it.mkdirs() }
        File(kitDir, Constants.Kit.CONFIG_FILE).writeText(yaml)
        if (hookScript.isNotBlank()) {
            val binDir = File(kitDir, "bin").also { it.mkdirs() }
            val script = File(binDir, "update-catalogs.sh")
            script.writeText("#!/bin/sh\n$hookScript\n")
            Files.setPosixFilePermissions(
                script.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        return kitDir
    }

    @Test
    fun `post-workload-start hook fires for matching trigger`() {
        val outputFile = File(workingDir, "hook-ran.txt")
        writeKit(
            name = "presto",
            yaml =
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            hookScript = """echo ran > "${outputFile.absolutePath}"""",
        )
        executor.firePostKitStart("cassandra")
        assertThat(outputFile).exists()
    }

    @Test
    fun `post-workload-stop hook fires for matching trigger`() {
        val outputFile = File(workingDir, "stop-hook-ran.txt")
        writeKit(
            name = "presto",
            yaml =
                """
                name: presto
                hooks:
                  post-workload-stop:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            hookScript = """echo ran > "${outputFile.absolutePath}"""",
        )
        executor.firePostKitStop("cassandra")
        assertThat(outputFile).exists()
    }

    @Test
    fun `hook skipped when triggering kit is the same as declaring kit`() {
        val outputFile = File(workingDir, "self-skip.txt")
        writeKit(
            name = "presto",
            yaml =
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            hookScript = """echo ran > "${outputFile.absolutePath}"""",
        )
        executor.firePostKitStart("presto")
        assertThat(outputFile).doesNotExist()
    }

    @Test
    fun `hook skipped when trigger not in scoped workloads list`() {
        val outputFile = File(workingDir, "scoped-skip.txt")
        writeKit(
            name = "presto",
            yaml =
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                    workloads:
                      - cassandra
                """.trimIndent(),
            hookScript = """echo ran > "${outputFile.absolutePath}"""",
        )
        executor.firePostKitStart("clickhouse")
        assertThat(outputFile).doesNotExist()
    }

    @Test
    fun `hook fires when trigger matches scoped workloads list`() {
        val outputFile = File(workingDir, "scoped-match.txt")
        writeKit(
            name = "presto",
            yaml =
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                    workloads:
                      - cassandra
                      - clickhouse
                """.trimIndent(),
            hookScript = """echo ran > "${outputFile.absolutePath}"""",
        )
        executor.firePostKitStart("cassandra")
        assertThat(outputFile).exists()
    }

    @Test
    fun `hook skipped when kit directory has no kit yaml`() {
        File(workingDir, "no-config").mkdirs()
        executor.firePostKitStart("cassandra")
        // No exception, no HookFailed — just silently skipped
        assertThat(capturedEvents.filterIsInstance<Event.Kit.HookFailed>()).isEmpty()
    }

    @Test
    fun `hook skipped when kit yaml has no hooks block`() {
        val outputFile = File(workingDir, "no-hooks.txt")
        writeKit(
            name = "presto",
            yaml = "name: presto\n",
            hookScript = """echo ran > "${outputFile.absolutePath}"""",
        )
        executor.firePostKitStart("cassandra")
        assertThat(outputFile).doesNotExist()
    }

    @Test
    fun `HookFailed event emitted when hook script exhausts retries`() {
        writeKit(
            name = "presto",
            yaml =
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            hookScript = "exit 1",
        )
        executor.firePostKitStart("cassandra")
        val failures = capturedEvents.filterIsInstance<Event.Kit.HookFailed>()
        assertThat(failures).hasSize(1)
        assertThat(failures[0].kit).isEqualTo("presto")
        assertThat(failures[0].hook).isEqualTo("bin/update-catalogs.sh")
    }

    /**
     * Starting a hook script can fail with a checked IOException, for example "Text file busy"
     * right after the script was written. The hook retry is meant to cover every failure but an
     * interrupt, so that start must be retried, not reported on the first attempt.
     */
    @Test
    fun `a hook script that fails to start is retried and then runs`() {
        val outputFile = File(workingDir, "retried.txt")
        writeHookKit("""echo ran > "${outputFile.absolutePath}"""")
        var launches = 0
        val retrying =
            executorStarting { builder ->
                launches++
                if (launches == 1) throw IOException("Cannot run program: error=26, Text file busy")
                builder.start()
            }

        retrying.firePostKitStart("cassandra")

        assertThat(launches).isEqualTo(2)
        assertThat(outputFile).exists()
        assertThat(capturedEvents.filterIsInstance<Event.Kit.HookFailed>()).isEmpty()
    }

    /** A missing script will not appear by waiting, so it is reported at once and nothing is started. */
    @Test
    fun `a missing hook script fails at once without starting a process`() {
        val kitDir = writeHookKit("")
        var launches = 0
        val executor =
            executorStarting {
                launches++
                it.start()
            }

        executor.firePostKitStart("cassandra")

        assertThat(launches).isZero()
        val script = File(kitDir, "bin/update-catalogs.sh").canonicalPath
        assertThat(capturedEvents.filterIsInstance<Event.Kit.HookFailed>().map { it.reason })
            .containsExactly("hook script not found: $script")
    }

    /** Nor will a missing execute bit: it is reported at once and nothing is started. */
    @Test
    fun `a hook script without the execute bit fails at once without starting a process`() {
        val kitDir = writeHookKit("true")
        val script = File(kitDir, "bin/update-catalogs.sh")
        Files.setPosixFilePermissions(script.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        var launches = 0
        val executor =
            executorStarting {
                launches++
                it.start()
            }

        executor.firePostKitStart("cassandra")

        assertThat(launches).isZero()
        assertThat(capturedEvents.filterIsInstance<Event.Kit.HookFailed>().map { it.reason })
            .containsExactly("hook script is not executable: ${script.canonicalPath}")
    }

    /** An interrupt asks the thread to stop: the hook is not run again, and the interrupt survives. */
    @Test
    fun `an interrupted hook is not retried and keeps the interrupt`() {
        writeHookKit("true")
        var launches = 0
        val interrupted =
            executorStarting {
                launches++
                throw InterruptedException("stop")
            }

        try {
            interrupted.firePostKitStart("cassandra")

            assertThat(launches).isEqualTo(1)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(capturedEvents.filterIsInstance<Event.Kit.HookFailed>()).hasSize(1)
        } finally {
            Thread.interrupted()
        }
    }

    private fun writeHookKit(hookScript: String) =
        writeKit(
            name = "presto",
            yaml =
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            hookScript = hookScript,
        )

    /** An executor that allows three attempts with no backoff, starting each hook through [startProcess]. */
    private fun executorStarting(startProcess: (ProcessBuilder) -> Process) =
        DefaultKitHookExecutor(
            context = get(),
            clusterStateManager = mockClusterStateManager,
            eventBus = eventBus,
            maxAttempts = 3,
            backoffBaseMs = 0L,
            startProcess = startProcess,
        )

    @Test
    fun `hook skipped when declaring kit is not in running kits`() {
        val stoppedState = clusterState.copy(runningKits = emptySet())
        whenever(mockClusterStateManager.load()).thenReturn(stoppedState)

        val outputFile = File(workingDir, "stopped-kit-skip.txt")
        writeKit(
            name = "presto",
            yaml =
                """
                name: presto
                hooks:
                  post-workload-start:
                    script: bin/update-catalogs.sh
                """.trimIndent(),
            hookScript = """echo ran > "${outputFile.absolutePath}"""",
        )
        executor.firePostKitStart("cassandra")
        assertThat(outputFile).doesNotExist()
    }
}
