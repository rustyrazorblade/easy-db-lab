package com.rustyrazorblade.easydblab.commands.observability

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.localstack.DashboardTreeSource
import com.rustyrazorblade.easydblab.localstack.LocalDashboardsRefresher
import com.rustyrazorblade.easydblab.localstack.LocalStackDashboards
import com.rustyrazorblade.easydblab.localstack.LocalStackSpec
import com.rustyrazorblade.easydblab.localstack.LocalStackWriter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import picocli.CommandLine
import java.io.File
import java.time.Duration

/**
 * `observability local dashboards <dir> [--from <dir>]`: it refreshes only the dashboards of a
 * running stack, through the real installer, and changes nothing when the stack's Grafana does not
 * answer. Grafana is a fake at its HTTP boundary.
 */
class LocalDashboardsTest : BaseKoinTest() {
    private val grafana = FakeLocalGrafana().apply { running = true }
    private val events = mutableListOf<Event>()
    private val contexts = mutableListOf<DashboardInstallContext>()

    private val packaged =
        object : DashboardTreeSource {
            override val origin: String = "packaged dashboards"

            override fun render(context: DashboardInstallContext): Map<String, String> {
                contexts += context
                return mapOf("core/new.json" to """{"uid":"new"}""")
            }
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    LocalDashboardsRefresher(
                        grafana,
                        LocalStackDashboards(grafana, packaged, reloadAttempts = RELOADS, reloadInterval = Duration.ofMillis(1)),
                        get(),
                    )
                }
            },
        )

    private companion object {
        const val RELOADS = 3
    }

    @BeforeEach
    fun listen() {
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    /** A stack directory as `local create` leaves it, with Grafana on 3300 and one old dashboard. */
    private fun stack(): File {
        val dir = File(tempDir, "lab-local")
        val spec =
            LocalStackSpec(
                mode = LocalStackSpec.Mode.Live("acct", "us-west-2", "lab", home = "acme"),
                tenants = listOf("default"),
                grafanaPort = 3300,
                documentsPort = 3380,
            )
        LocalStackWriter("7").write(dir, spec, mapOf("compose.yml" to "services: {}\n".toByteArray()))
        File(dir, "dashboards/current/core/old.json").apply { parentFile.mkdirs() }.writeText("""{"uid":"old"}""")
        return dir
    }

    private fun refresh(vararg args: String) = LocalDashboards().also { CommandLine(it).parseArgs(*args) }.execute()

    private fun tree(dir: File): Map<String, String> {
        val current = File(dir, "dashboards/current")
        return current
            .walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(current).invariantSeparatorsPath to it.readText() }
    }

    @Test
    fun `with no --from the packaged dashboards replace the tree, Grafana reloads them, and the output says so`() {
        val dir = stack()

        refresh(dir.path)

        assertThat(tree(dir)).isEqualTo(mapOf("core/new.json" to """{"uid":"new"}"""))
        assertThat(grafana.dashboardReloads).containsExactly(3300)
        assertThat(grafana.datasourceReloads).isEmpty()
        val installed = events.filterIsInstance<Event.LocalStack.DashboardsInstalled>().single()
        assertThat(installed.from).isNull()
        assertThat(installed.toDisplayString()).contains("Using packaged dashboards")
        assertThat(contexts.single().tenants.home).isEqualTo("acme")
        assertThat(contexts.single().documentsUrl).isEqualTo("http://127.0.0.1:3380")
    }

    @Test
    fun `--from takes the dashboards from that directory`() {
        val dir = stack()
        val work =
            File(
                tempDir,
                "work",
            ).apply { File(this, "mine/edit.json").apply { parentFile.mkdirs() }.writeText("""{"uid":"edit"}""") }

        refresh(dir.path, "--from", work.path)

        assertThat(tree(dir).keys).containsExactly("mine/edit.json")
        assertThat(events.filterIsInstance<Event.LocalStack.DashboardsInstalled>().single().from).isEqualTo(work.path)
        assertThat(grafana.dashboardReloads).containsExactly(3300)
    }

    @Test
    fun `a stopped stack is refused, named, and its tree is left as it was`() {
        val dir = stack()
        val before = tree(dir)
        grafana.running = false

        assertThatThrownBy { refresh(dir.path) }.isInstanceOf(CommandFailedException::class.java)

        assertThat(tree(dir)).isEqualTo(before)
        assertThat(File(dir, "dashboards").list()).containsExactly("current")
        assertThat(grafana.dashboardReloads).isEmpty()
        val refused = events.filterIsInstance<Event.LocalStack.GrafanaNotRunning>().single()
        assertThat(refused.grafanaUrl).isEqualTo("http://127.0.0.1:3300")
        assertThat(refused.isError()).isTrue()
    }

    @Test
    fun `a refused dashboards reload fails with a typed event that says the tree was replaced`() {
        val dir = stack()
        grafana.refuses = "dashboards"

        assertThatThrownBy { refresh(dir.path) }.isInstanceOf(CommandFailedException::class.java)

        val refused = events.filterIsInstance<Event.LocalStack.ReloadRefused>().single()
        assertThat(refused.kind).isEqualTo("dashboards")
        assertThat(refused.grafanaUrl).isEqualTo("http://127.0.0.1:3300")
        assertThat(events.filterIsInstance<Event.LocalStack.DashboardsInstalled>()).isEmpty()
    }

    @Test
    fun `a tree Grafana does not serve after every reload fails with a typed event naming the missing dashboards`() {
        val dir = stack()
        grafana.serves = false

        assertThatThrownBy { refresh(dir.path) }.isInstanceOf(CommandFailedException::class.java)

        assertThat(grafana.dashboardReloads).hasSize(RELOADS)
        val notServed = events.filterIsInstance<Event.LocalStack.DashboardsNotServed>().single()
        assertThat(notServed.missing).containsExactly("new")
        assertThat(notServed.reloads).isEqualTo(RELOADS)
        assertThat(notServed.grafanaUrl).isEqualTo("http://127.0.0.1:3300")
        assertThat(notServed.isError()).isTrue()
        assertThat(events.filterIsInstance<Event.LocalStack.DashboardsInstalled>()).isEmpty()
    }

    @Test
    fun `a --from directory with a misplaced dashboard changes nothing`() {
        val dir = stack()
        val before = tree(dir)
        val work = File(tempDir, "work").apply { mkdirs() }
        File(work, "loose.json").writeText("{}")

        assertThatThrownBy { refresh(dir.path, "--from", work.path) }.hasMessageContaining("loose.json")

        assertThat(tree(dir)).isEqualTo(before)
        assertThat(grafana.dashboardReloads).isEmpty()
    }

    @Test
    fun `a directory with no stack in it is refused and named`() {
        val dir = File(tempDir, "nothing-here").apply { mkdirs() }

        assertThatThrownBy { refresh(dir.path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(dir.path)
            .hasMessageContaining("local create")
    }
}
