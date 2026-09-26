package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ClickHouse `start` must not return until every replica is up. The Altinity operator creates the
 * replicas one at a time, so waiting for the pods that exist to be Ready returned after the first
 * replica while the ClickHouseInstallation was still InProgress (1/3 pods). The step now waits for
 * the installation to report Completed, then for every replica pod to be Ready. The step's script
 * runs here against a stub `kubectl` function that records each call.
 */
class ClickHouseKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("clickhouse", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private val stubDir by lazy { File(tempDir, "stub").also { it.mkdirs() } }
    private val calls by lazy { File(stubDir, "kubectl.log") }

    /** The start step that follows applying the ClickHouseInstallation. */
    private fun installationWaitStep(): InstallStep.Shell {
        val start = kit.config.start
        val applied = start.indexOfFirst { it is InstallStep.Manifest && it.template == "clickhouseinstallation.yaml" }
        return start.drop(applied + 1).filterIsInstance<InstallStep.Shell>().first()
    }

    /**
     * Runs the step with `kubectl` as a shell function that records its arguments and fails the
     * calls whose arguments contain [failOn].
     *
     * The stub is a function, not an executable written to disk and found on `PATH`: macOS
     * assesses the first exec of every new executable file through one system-wide queue
     * (~150ms each, measured with no CPU in use), so under `check` — where the script-test tasks
     * create stubs of their own — a fresh `kubectl` file waited out the timeout before it ran.
     */
    private fun runAgainstStub(failOn: String = "<never>"): Int {
        val stub =
            """
            kubectl() {
              echo "$*" >> "${calls.absolutePath}"
              case "$*" in *"$failOn"*) return 1 ;; esac
              return 0
            }
            """.trimIndent()
        val output = File(stubDir, "script.out")
        val process =
            ProcessBuilder("bash", "-c", stub + "\n" + installationWaitStep().script)
                .directory(stubDir)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
        // A step that polls for pods to appear never sees any from the stub; fail instead of hanging.
        check(process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            val recorded = if (calls.exists()) calls.readLines() else emptyList()
            "the step did not finish within ${SCRIPT_TIMEOUT_SECONDS}s; calls: $recorded; output: ${output.readText()}"
        }
        return process.exitValue()
    }

    private companion object {
        const val SCRIPT_TIMEOUT_SECONDS = 20L
        const val KEEPER_METRICS_PORT = 7000

        /** The label the Altinity operator puts on every pod of the `clickhouse-keeper` CHK. */
        const val KEEPER_POD_SELECTOR = "clickhouse-keeper.altinity.com/chk=clickhouse-keeper"

        /** The ports a Keeper pod already listens on: client, raft and http_control. */
        val KEEPER_LISTEN_PORTS = listOf(2181, 9444, 9182)
    }

    @Test
    fun `start waits for the installation to complete before waiting for the replica pods`() {
        assertThat(runAgainstStub()).isEqualTo(0)

        val invocations = calls.readLines()
        val completed = invocations.indexOfFirst { "wait" in it && "{.status.status}=Completed" in it && "chi/clickhouse" in it }
        val podsReady =
            invocations.indexOfFirst {
                "wait" in it && "condition=Ready" in it && "clickhouse.altinity.com/chi=clickhouse" in it
            }
        assertThat(completed).isNotNegative()
        assertThat(podsReady).isGreaterThan(completed)
    }

    @Test
    fun `start fails without waiting on pods when the installation does not complete`() {
        assertThat(runAgainstStub(failOn = "{.status.status}=Completed")).isNotEqualTo(0)

        assertThat(calls.readLines()).noneSatisfy { assertThat(it).contains("condition=Ready") }
    }

    private fun keeper(): GenericKubernetesResource =
        kit.render("clickhouse-keeper.yaml.template").filterIsInstance<GenericKubernetesResource>().single()

    /**
     * The Keeper pod template carries Keeper's db-only node affinity and the
     * `app.kubernetes.io/instance` label. Declaring it under `templates.podTemplates` is not
     * enough: the operator applies a pod template only when one is referenced, so Keeper pods ran
     * without the label and one was scheduled on app0.
     */
    @Test
    fun `keeper pods use the pod template that pins them to db nodes and labels them`() {
        val chk = keeper()
        val applied = chk.get<String>("spec", "defaults", "templates", "podTemplate")
        val podTemplate =
            GenericKubernetesResource().apply {
                additionalProperties["template"] =
                    chk
                        .get<List<Map<String, Any>>>("spec", "templates", "podTemplates")
                        .single { it["name"] == applied }
            }

        assertThat(podTemplate.get<Map<String, String>>("template", "metadata", "labels"))
            .containsEntry("app.kubernetes.io/instance", "clickhouse")
        val terms =
            podTemplate.get<List<Map<String, Any>>>(
                "template",
                "spec",
                "affinity",
                "nodeAffinity",
                "requiredDuringSchedulingIgnoredDuringExecution",
                "nodeSelectorTerms",
            )
        assertThat(terms.flatMap { it["matchExpressions"] as List<*> })
            .containsExactly(mapOf("key" to "type", "operator" to "In", "values" to listOf("db")))
    }

    /** The CHK's `spec.configuration.settings`, each value as a string. */
    private fun keeperSettings(): Map<String, String> =
        keeper()
            .get<Map<String, Any>>("spec", "configuration", "settings")
            .mapValues { it.value.toString() }

    /**
     * Keeper serves no Prometheus endpoint unless its config enables one, so the dashboard's Keeper
     * panels had nothing to show. The port must not be one Keeper already listens on: 2181
     * (client), 9444 (raft) or 9182 (http_control).
     */
    @Test
    fun `keeper serves its metrics, events and asynchronous metrics over Prometheus on a free port`() {
        val settings = keeperSettings()

        assertThat(settings).containsEntry("prometheus/endpoint", "/metrics")
        assertThat(settings).containsEntry("prometheus/metrics", "true")
        assertThat(settings).containsEntry("prometheus/events", "true")
        assertThat(settings).containsEntry("prometheus/asynchronous_metrics", "true")
        assertThat(settings["prometheus/port"]).isEqualTo(KEEPER_METRICS_PORT.toString())
        assertThat(KEEPER_METRICS_PORT).isNotIn(KEEPER_LISTEN_PORTS)
    }

    /**
     * Keeper's endpoint is scraped by pod discovery on the Keeper pods — the ones the start step
     * waits on — at the port and path its settings serve. Its job must differ from the server
     * scrape's (the kit name), since the metrics registry refuses two targets with one job.
     */
    @Test
    fun `keeper is scraped on its own pods at the endpoint its settings serve, under a job of its own`() {
        val settings = keeperSettings()
        val keeperScrape = kit.scrapeMetrics.single { it.podSelector == KEEPER_POD_SELECTOR }

        assertThat(keeperScrape.port.toString()).isEqualTo(settings["prometheus/port"])
        assertThat(keeperScrape.path).isEqualTo(settings["prometheus/endpoint"])
        assertThat(kit.scrapeMetrics.map { it.job.ifBlank { kit.config.name } }).doesNotHaveDuplicates()
        assertThat(
            kit.config.start
                .filterIsInstance<InstallStep.Shell>()
                .first()
                .script,
        ).contains("-l $KEEPER_POD_SELECTOR")
    }
}
