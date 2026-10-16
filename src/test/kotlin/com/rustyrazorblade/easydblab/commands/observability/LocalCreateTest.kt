package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.export.ExportStackFiles
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.DashboardTreeSource
import com.rustyrazorblade.easydblab.localstack.LocalGrafana
import com.rustyrazorblade.easydblab.localstack.LocalStackCreator
import com.rustyrazorblade.easydblab.localstack.LocalStackDashboards
import com.rustyrazorblade.easydblab.localstack.LocalStackDirectoryRefused
import com.rustyrazorblade.easydblab.localstack.LocalStackStore
import com.rustyrazorblade.easydblab.localstack.LocalStackWriter
import com.rustyrazorblade.easydblab.localstack.StoreReadResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import picocli.CommandLine
import java.io.File

/**
 * `observability local create <dir> [--tenant]`: the directory contract, the store check and the
 * re-run, through the real composer, writer and dashboard installer. The account bucket and the
 * local Grafana are fakes at their boundaries.
 */
class LocalCreateTest : BaseKoinTest() {
    /** The bucket's tenant directories, as the next listing will see them. */
    private val storeTenants = mutableListOf("acme")
    private var storeResult: StoreReadResult = StoreReadResult.Readable(region = "eu-west-1")
    private var storeChecks = 0
    private val listedRegions = mutableListOf<String>()

    private val store =
        object : LocalStackStore {
            override fun check(bucket: String): StoreReadResult {
                storeChecks++
                return storeResult
            }

            override fun tenants(
                bucket: String,
                region: String,
                home: String,
            ): TenantSet {
                listedRegions += region
                return TenantSet.of(home, storeTenants.toList())
            }
        }

    private val grafana = FakeLocalGrafana()
    private val dashboardContexts = mutableListOf<DashboardInstallContext>()
    private val events = mutableListOf<Event>()

    private val dashboardSource =
        object : DashboardTreeSource {
            override val origin: String = "packaged dashboards"

            override fun render(context: DashboardInstallContext): Map<String, String> {
                dashboardContexts += context
                return mapOf("home/h.json" to """{"uid":"h"}""")
            }
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    LocalStackCreator(
                        user = get(),
                        store = store,
                        writer = LocalStackWriter(toolVersion = "7"),
                        grafana = grafana,
                        dashboards = LocalStackDashboards(grafana, dashboardSource),
                        eventBus = get(),
                    )
                }
            },
        )

    @BeforeEach
    fun setup() {
        get<User>().apply {
            awsProfile = "lab-profile"
            s3Bucket = "acct-bucket"
        }
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun create(vararg args: String) = LocalCreate().also { CommandLine(it).parseArgs(*args) }.execute()

    private fun stack(name: String = "lab-local") = File(tempDir, name)

    private fun snapshot(dir: File): Map<String, String> =
        dir
            .walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }

    @Test
    fun `the directory is required and the error names the argument`() {
        assertThatThrownBy { CommandLine(LocalCreate()).parseArgs() }
            .isInstanceOf(CommandLine.MissingParameterException::class.java)
            .hasMessageContaining("DIR")
    }

    @Test
    fun `a new directory gets the stack, its manifest and the packaged dashboards for the live stack`() {
        val dir = stack()

        create(dir.path)

        assertThat(File(dir, Constants.LocalStack.COMPOSE_FILE)).isFile()
        assertThat(File(dir, Constants.LocalStack.MANIFEST_FILE)).isFile()
        assertThat(File(dir, "dashboards/current/home/h.json").readText()).isEqualTo("""{"uid":"h"}""")
        val written = events.filterIsInstance<Event.LocalStack.Written>().single()
        assertThat(written.tenants).containsExactly("acme", "default")
        assertThat(written.homeTenant).isEqualTo("default")
        assertThat(written.edited).isEmpty()
        assertThat(dashboardContexts.single()).isEqualTo(
            DashboardInstallContext(
                cluster = null,
                experiment = null,
                tenants = TenantSet.of("default", listOf("acme")),
                documentsUrl = "http://127.0.0.1:${Constants.LocalStack.DEFAULT_DOCUMENTS_PORT}",
            ),
        )
        assertThat(File(dir, "config/store-gate.conf")).isFile()
        assertThat(File(dir, Constants.LocalStack.COMPOSE_FILE).readText())
            .contains("lab-profile")
            .contains("eu-west-1")
    }

    @Test
    fun `the signer signs for, and the tenants are listed in, the bucket's region from the store check`() {
        storeResult = StoreReadResult.Readable(region = "ap-south-1")
        val dir = stack()

        create(dir.path)

        assertThat(listedRegions).containsExactly("ap-south-1")
        val compose = ComposeFile.fromYaml(File(dir, Constants.LocalStack.COMPOSE_FILE).readText())
        val signer = checkNotNull(compose.services[Constants.LocalStack.Services.STORE_SIGNER])
        assertThat(signer.command).containsSequence("--region", "ap-south-1")
        assertThat(signer.command).containsSequence("--host", "s3.ap-south-1.amazonaws.com")
    }

    @Test
    fun `--tenant sets the home tenant`() {
        create(stack().path, "--tenant", "acme")

        val written = events.filterIsInstance<Event.LocalStack.Written>().single()
        assertThat(written.homeTenant).isEqualTo("acme")
        assertThat(written.tenants).containsExactly("acme")
    }

    @Test
    fun `a tenant that breaks the tenant rule is refused before anything is read or written`() {
        val dir = stack()

        assertThatThrownBy { create(dir.path, "--tenant", "Bad/Tenant") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(Constants.Observability.TENANT_PATTERN)
        assertThat(dir).doesNotExist()
        assertThat(storeChecks).isZero()
    }

    @Test
    fun `a foreign directory is refused and named, before the store is read, and nothing in it changes`() {
        val dir = stack().apply { mkdirs() }
        File(dir, "notes.txt").writeText("mine")

        assertThatThrownBy { create(dir.path) }
            .isInstanceOf(LocalStackDirectoryRefused::class.java)
            .hasMessageContaining(dir.path)
        assertThat(snapshot(dir)).isEqualTo(mapOf("notes.txt" to "mine"))
        assertThat(storeChecks).isZero()
    }

    @Test
    fun `an opened export is refused, since create would turn its offline stack into a live one`() {
        val dir = stack().apply { mkdirs() }
        val export = ExportStackFiles(dashboardSource, toolVersion = "7").files(tenant = "acme")
        export.forEach { (path, bytes) -> File(dir, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
        val before = snapshot(dir)

        assertThatThrownBy { create(dir.path) }
            .isInstanceOf(LocalStackDirectoryRefused::class.java)
            .hasMessageContaining(dir.path)
            .hasMessageContaining("export")
        assertThat(snapshot(dir)).isEqualTo(before)
        assertThat(storeChecks).isZero()
    }

    @Test
    fun `an unreadable store fails with the profile and AWS's code, and writes nothing`() {
        storeResult = StoreReadResult.Unreadable(awsErrorCode = "ExpiredToken")
        val dir = stack()

        assertThatThrownBy { create(dir.path) }.isInstanceOf(CommandFailedException::class.java)

        val unreadable = events.filterIsInstance<Event.LocalStack.StoreUnreadable>().single()
        assertThat(unreadable).isEqualTo(Event.LocalStack.StoreUnreadable(profile = "lab-profile", awsErrorCode = "ExpiredToken"))
        assertThat(unreadable.toDisplayString()).contains("aws sso login --profile lab-profile")
        assertThat(dir).doesNotExist()
    }

    @Test
    fun `a profile with no AWS profile name is refused, since the signer reads the profile from ~ aws`() {
        get<User>().awsProfile = ""

        assertThatThrownBy { create(stack().path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("AWS profile")
        assertThat(storeChecks).isZero()
    }

    @Test
    fun `a re-run leaves data, state and the override byte-identical and rewrites only owned files`() {
        val dir = stack()
        create(dir.path)
        val untouched =
            mapOf(
                "data/mimir/block" to "data",
                "cache/mimir/tsdb" to "cache",
                "grafana-data/grafana.db" to "grafana",
                Constants.LocalStack.OVERRIDE_FILE to "services: {}\n",
            )
        untouched.forEach { (path, text) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(text) }

        create(dir.path)

        untouched.forEach { (path, text) -> assertThat(File(dir, path).readText()).describedAs(path).isEqualTo(text) }
        assertThat(events.filterIsInstance<Event.LocalStack.Written>().last().edited).isEmpty()
    }

    @Test
    fun `an owned file the user edited is overwritten and named`() {
        val dir = stack()
        create(dir.path)
        val gate = File(dir, "config/store-gate.conf")
        val original = gate.readText()
        gate.writeText("# my edit\n")

        create(dir.path)

        assertThat(gate.readText()).isEqualTo(original)
        val written = events.filterIsInstance<Event.LocalStack.Written>().last()
        assertThat(written.edited).containsExactly("config/store-gate.conf")
        assertThat(written.toDisplayString()).contains("config/store-gate.conf")
    }

    @Test
    fun `a re-run with Grafana up lists the tenants again and reloads its datasources and dashboards`() {
        val dir = stack()
        create(dir.path)
        storeTenants += "beta"
        grafana.running = true

        create(dir.path)

        assertThat(File(dir, "config/grafana/provisioning/datasources/datasources.yaml").readText()).contains("mimir-beta")
        assertThat(grafana.datasourceReloads).containsExactly(Constants.LocalStack.DEFAULT_GRAFANA_PORT)
        assertThat(grafana.dashboardReloads).containsExactly(Constants.LocalStack.DEFAULT_GRAFANA_PORT)
        assertThat(events.filterIsInstance<Event.LocalStack.DatasourcesReloaded>()).hasSize(1)
    }

    @Test
    fun `a first create never reloads a Grafana that already answers on the port, which is not this stack's`() {
        grafana.running = true

        create(stack().path)

        assertThat(grafana.datasourceReloads).isEmpty()
        assertThat(grafana.dashboardReloads).isEmpty()
    }
}

/** A local Grafana that is up or down as the test sets it, and records the reloads it was asked for. */
class FakeLocalGrafana : LocalGrafana {
    var running = false
    val datasourceReloads = mutableListOf<Int>()
    val dashboardReloads = mutableListOf<Int>()

    override fun isRunning(port: Int): Boolean = running

    override fun reloadDatasources(port: Int) {
        datasourceReloads += port
    }

    override fun reloadDashboards(port: Int) {
        dashboardReloads += port
    }
}
