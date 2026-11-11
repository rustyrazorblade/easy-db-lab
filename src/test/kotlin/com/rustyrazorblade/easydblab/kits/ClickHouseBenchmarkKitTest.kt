package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.install.KitRunnerCommandFactory
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitRuntime
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The clickhouse-benchmark kit's `kit.yaml` is the contract its scripts read: the args reach them
 * as environment variables with these defaults, the runtime probe guards `start`, and the kit-ref
 * target must offer the native and http endpoints the scripts use. Only the three lifecycle scripts
 * may become commands; the kit's `lib/` and `pod/` code must not.
 */
class ClickHouseBenchmarkKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("clickhouse-benchmark", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private fun defaults(args: List<KitArgSpec>) = args.associate { it.flag to it.default }

    @Test
    fun `install args name a target with native and http endpoints and the image version`() {
        val config = kit.config

        assertThat(config.type).isEqualTo(KitType.APP)
        val target = config.args.single { it.flag == "--target" }
        assertThat(target.type).isEqualTo(KitArgSpec.ArgType.KIT_REF)
        assertThat(target.required).isTrue()
        assertThat(target.capability).isEmpty()
        assertThat(target.endpoints).containsExactly(KitEndpoint.EndpointType.NATIVE, KitEndpoint.EndpointType.HTTP)
        assertThat(config.args.single { it.flag == "--version" }.default).isEqualTo("latest")
    }

    @Test
    fun `prepare and start args and their defaults`() {
        val prepare = requireNotNull(kit.config.commands["prepare"]).args
        assertThat(defaults(prepare)).containsExactlyInAnyOrderEntriesOf(
            mapOf("--workload" to "clickbench", "--source" to "", "--storage-policy" to "default", "--recreate" to "false"),
        )
        assertThat(prepare.single { it.flag == "--recreate" }.type).isEqualTo(KitArgSpec.ArgType.BOOLEAN)
        assertThat(prepare.single { it.flag == "--source" }.description)
            .contains("https://clickhouse-public-datasets.s3.amazonaws.com/hits_compatible/athena_partitioned/hits_{0..99}.parquet")

        val start = requireNotNull(kit.config.commands["start"]).args
        assertThat(defaults(start)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "--workload" to "",
                "--queries" to "",
                "--concurrency" to "4",
                "--duration" to "60",
                "--iterations" to "0",
                "--randomize" to "true",
                "--continue-on-errors" to "false",
                "--settings" to "",
            ),
        )
        assertThat(start.single { it.flag == "--workload" }.description).contains("(default clickbench)")
        assertThat(start.filter { it.type == KitArgSpec.ArgType.BOOLEAN }.map { it.flag })
            .containsExactlyInAnyOrder("--randomize", "--continue-on-errors")
        assertThat(start.filter { it.type == KitArgSpec.ArgType.INT }.map { it.flag })
            .containsExactlyInAnyOrder("--concurrency", "--duration", "--iterations")
    }

    @Test
    fun `the runtime probe watches the benchmark run pods and start is collision checked`() {
        val runtime = requireNotNull(kit.config.runtime)

        assertThat(runtime.type).isEqualTo(KitRuntime.RuntimeType.PODS)
        assertThat(runtime.namespace).isEqualTo("default")
        assertThat(parseLabelSelector(runtime.selector))
            .isEqualTo(mapOf("easydblab/kit" to "\${KIT_NAME}", "easydblab/role" to "benchmark-run"))
        assertThat(kit.config.collisionCheck.guards("start")).isTrue()
    }

    @Test
    fun `only prepare, start and stop become commands`() {
        val kitDir = File(tempDir, "clickhouse-benchmark-clickhouse").apply { mkdirs() }
        val entries =
            InstallTemplateResolver(context, KitSourcesProvider(context))
                .listTemplateFiles(InstallTemplateResolver.TemplateSource.Builtin("clickhouse-benchmark"))
        entries.forEach { entry ->
            File(kitDir, entry.name.removeSuffix(".template")).apply {
                parentFile.mkdirs()
                writeText(entry.content)
                if (entry.name.startsWith("bin/")) setExecutable(true)
            }
        }
        File(kitDir, "kit.yaml").writeText(kit.resource("kit.yaml"))

        val group = KitRunnerCommandFactory().buildKitGroup("clickhouse-benchmark-clickhouse", kitDir)

        assertThat(entries.map { it.name }).contains("lib/args.sh", "pod/run-benchmark.sh", "pod/prepare-load.sh")
        assertThat(group.subcommands.keys).containsExactlyInAnyOrder("prepare", "start", "stop", "status")
    }
}
