package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.CommandLineParser
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.DefaultHelpTopicService
import com.rustyrazorblade.easydblab.services.DefaultKitCommandScanner
import com.rustyrazorblade.easydblab.services.HelpTopicService
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitCommandScanner
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.WorkspaceKitScanner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Proves that `kit install <kit> --source <dir>` installs the kit from `<dir>` for that call only.
 *
 * Several agents on one machine share one registered kit source per name. Pointing that shared
 * source at a bisect copy made the other agents install the copy by mistake, so `--source` must
 * neither use nor change the registered setting. Each test drives the real parser, resolver, and
 * kit-sources file; only cluster state is mocked, because loading it needs a provisioned cluster.
 */
class KitInstallSourceTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()

    private lateinit var sharedDir: File
    private lateinit var perCallDir: File
    private lateinit var kitSourcesFile: File

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
                single { WorkspaceKitScanner(get()) }
                single<KitCommandScanner> { DefaultKitCommandScanner() }
                single<HelpTopicService> { DefaultHelpTopicService() }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState())
        sharedDir = writeKit(File(tempDir, "shared-kits"), variant = "shared")
        perCallDir = writeKit(File(tempDir, "bisect-kits"), variant = "bisect", extraArg = "--bisect-only")
        get<KitSourcesProvider>().add(SHARED_SOURCE, sharedDir.path)
        kitSourcesFile = File(context.profileDir, "kit-sources.yaml")
    }

    @Test
    fun `install with --source reads the kit from that directory and leaves the shared source unchanged`() {
        val sharedSettingBefore = kitSourcesFile.readText()
        val args = arrayOf("kit", "install", KIT, "--source", perCallDir.path, "--bisect-only", "yes")

        val exitCode = install(args)

        assertThat(exitCode).isZero()
        assertThat(installedVariant()).isEqualTo("bisect")
        assertThat(File(context.workingDirectory, "$KIT/${Constants.Kit.RESOLVED_ARGS_FILE}").readText())
            .contains("BISECT_ONLY=yes")
        assertThat(kitSourcesFile.readText()).isEqualTo(sharedSettingBefore)
        assertThat(
            get<KitSourcesProvider>()
                .load()
                .sources
                .single()
                .path,
        ).isEqualTo(sharedDir.canonicalPath)
    }

    @Test
    fun `install with --source=dir in one token reads the kit from that directory`() {
        val args = arrayOf("kit", "install", KIT, "--source=${perCallDir.path}")

        val exitCode = install(args)

        assertThat(exitCode).isZero()
        assertThat(installedVariant()).isEqualTo("bisect")
    }

    @Test
    fun `install without --source reads the kit from the shared source`() {
        val args = arrayOf("kit", "install", KIT)

        val exitCode = install(args)

        assertThat(exitCode).isZero()
        assertThat(installedVariant()).isEqualTo("shared")
    }

    @Test
    fun `install with --source fails naming the directory and the kit when the directory lacks the kit`() {
        val emptyDir = File(tempDir, "no-kits").also { it.mkdirs() }
        val args = arrayOf("kit", "install", KIT, "--source", emptyDir.path)

        assertThatThrownBy { CommandLineParser(args).eval(args) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(emptyDir.canonicalPath)
            .hasMessageContaining("'$KIT'")
        assertThat(File(context.workingDirectory, KIT)).doesNotExist()
    }

    @Test
    fun `install with --source fails naming the file when the kit's kit yaml is not a valid descriptor`() {
        val brokenDir = File(tempDir, "broken-kits")
        val kitYaml = File(File(brokenDir, KIT).also { it.mkdirs() }, Constants.Kit.CONFIG_FILE)
        kitYaml.writeText("name: $KIT\nargs:\n  - flag: [not, a, string]\n")
        val args = arrayOf("kit", "install", KIT, "--source", brokenDir.path)

        assertThatThrownBy { CommandLineParser(args).eval(args) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(kitYaml.canonicalPath)
            .hasMessageContaining("not a valid kit descriptor")
        assertThat(File(context.workingDirectory, KIT)).doesNotExist()
    }

    @Test
    fun `install with --source rejects a kit name that leaves the directory`() {
        // perCallDir/../shared-kits/sourcekit has a valid kit.yaml, so only the name check stops it.
        val escaping = "../shared-kits/$KIT"
        val args = arrayOf("kit", "install", escaping, "--source", perCallDir.path)

        assertThatThrownBy { CommandLineParser(args).eval(args) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'$escaping'")
            .hasMessageContaining(perCallDir.canonicalPath)
    }

    /**
     * Builds the real parser for [args], parses them, and runs the matched install command.
     * `CommandLineParser.eval` would route through `DefaultCommandExecutor`, whose AWS and SSH
     * lifecycle is not what these tests are about.
     */
    private fun install(args: Array<String>): Int = matchedInstall(CommandLineParser(args), args).call()

    /** The install command [parser] matches for [args]. */
    private fun matchedInstall(
        parser: CommandLineParser,
        args: Array<String>,
    ): KitInstallCommand {
        val install =
            parser
                .commandLine
                .parseArgs(*args)
                .subcommand()
                .subcommand()
                .subcommand()
                .commandSpec()
                .userObject()
        assertThat(install).isInstanceOf(KitInstallCommand::class.java)
        return install as KitInstallCommand
    }

    private fun installedVariant(): String = File(context.workingDirectory, "$KIT/variant.txt").readText().trim()

    /** Writes a kit named [KIT] under [parent] whose `variant.txt` names [variant]. */
    private fun writeKit(
        parent: File,
        variant: String,
        extraArg: String? = null,
    ): File {
        val kitDir = File(parent, KIT).also { it.mkdirs() }
        val argsYaml =
            extraArg?.let {
                """
                |args:
                |  - flag: $it
                |    variable: BISECT_ONLY
                |    type: string
                |    description: Only the bisect copy declares this
                """.trimMargin()
            } ?: "args: []"
        File(kitDir, Constants.Kit.CONFIG_FILE).writeText("name: $KIT\ndescription: $variant copy\n$argsYaml\n")
        File(kitDir, "variant.txt").writeText("$variant\n")
        return parent
    }

    private fun clusterState(): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "account-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts =
                mapOf(
                    ServerType.Control to
                        listOf(ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-ctrl")),
                ),
        )

    private companion object {
        const val KIT = "sourcekit"
        const val SHARED_SOURCE = "shared"
    }
}
