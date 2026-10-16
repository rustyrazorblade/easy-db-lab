package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.export.ExportSelector
import com.rustyrazorblade.easydblab.export.ExportStackFiles
import com.rustyrazorblade.easydblab.export.ExportTarget
import com.rustyrazorblade.easydblab.export.ExportZipWriter
import com.rustyrazorblade.easydblab.export.FakeExportStore
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.localstack.DashboardTreeSource
import com.rustyrazorblade.easydblab.localstack.LocalStackSpec
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

/**
 * A query export end to end over fakes of its two temporary stacks (design section 7): only the
 * selected clusters' logs, traces and metrics, their annotation backups and their experiments'
 * reports go into the zip, whose stack has every tenant the clusters are in; each signal is reported,
 * zero as zero; the work directory is kept and its size reported, on success and on failure; a
 * failure leaves no zip; and nothing in the store is written.
 */
class QueryExporterTest {
    @TempDir
    lateinit var dir: File

    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = FakeExportStore()
    private val events = mutableListOf<Event>()
    private val eventBus = EventBus()

    /** The tenant of each cluster the reader knows, and the experiment it is labelled with ("" before the label). */
    private val clusters =
        mapOf(
            X1 to ("acme" to "exp-a"),
            Y2 to ("acme" to "exp-a"),
            Z3 to ("acme" to "exp-b"),
            W4 to ("default" to "exp-a"),
            OLD5 to ("acme" to ""),
            INJECTED to ("acme" to "exp-bad"),
        )

    /** Clusters that have traces. */
    private var traced = setOf(X1)
    private var failTraceFetch = false
    private val stopped = mutableListOf<String>()
    private val closed = mutableListOf<String>()

    private fun tenantsOf(header: String) = header.split('|')

    private val reader =
        FakeBackends { call ->
            val tenants = tenantsOf(call.tenant)
            when {
                call.route == "/prometheus/api/v1/series" -> {
                    val cluster = call.param("match[]")!!.substringAfter("cluster=\"").substringBefore('"')
                    val found = clusters[cluster]?.takeIf { it.first in tenants }
                    val series =
                        listOfNotNull(
                            found?.let {
                                mapOf("cluster" to cluster) +
                                    if (tenants.size > 1) mapOf("__tenant_id__" to it.first) else emptyMap()
                            },
                        )
                    FakeBackends.series(*series.toTypedArray())
                }
                call.route == "/prometheus/api/v1/label/cluster/values" -> {
                    val matches = call.params.filter { it.first == "match[]" }.map { it.second }
                    val values =
                        clusters
                            .filter { (cluster, where) ->
                                where.first in tenants &&
                                    matches.any { m ->
                                        val exp = m.substringAfter("experiment=\"", "\u0000").substringBefore('"')
                                        val named = m.substringAfter("cluster=\"", "\u0000").substringBefore('"')
                                        val among = m.substringAfter("cluster=~\"", "\u0000").substringBefore('"').split('|')
                                        when {
                                            m.contains("cluster=~") -> cluster in among && (exp == "\u0000" || where.second == exp)
                                            m.contains("experiment=\"\",cluster=") -> where.second == "" && cluster == named
                                            else -> where.second == exp
                                        }
                                    }
                            }.keys
                    FakeBackends.prometheus(JsonArray(values.map(::JsonPrimitive)).toString())
                }
                call.route == "/prometheus/api/v1/label/experiment/values" -> {
                    val among =
                        call
                            .param("match[]")!!
                            .substringAfter("cluster=~\"")
                            .substringBefore('"')
                            .split('|')
                    val values =
                        clusters
                            .filter { it.key in among && it.value.first in tenants && it.value.second.isNotEmpty() }
                            .values
                            .map { it.second }
                    FakeBackends.prometheus(values.distinct().joinToString(",", "[", "]") { "\"$it\"" })
                }
                call.route == "/prometheus/api/v1/query_range" -> {
                    val among =
                        call
                            .param("query")!!
                            .substringAfter("cluster=~\"")
                            .substringBefore('"')
                            .split('|')
                    val series =
                        among.filter { clusters[it]?.first in tenants }.joinToString(",") {
                            """{"metric":{"cluster":"$it"},"values":[[${now.epochSecond - 7200},"1"],[${now.epochSecond - 3600},"1"]]}"""
                        }
                    FakeBackends.prometheus("""{"resultType":"matrix","result":[$series]}""")
                }
                call.route == "/loki/api/v1/query_range" -> {
                    val cluster = call.param("query")!!.substringAfter("cluster=\"").substringBefore('"')
                    val from = call.param("start")!!.toLong()
                    val at = (now.epochSecond - 3000) * 1_000_000_000L
                    val values = if (at >= from) """[["$at","line of $cluster"]]""" else "[]"
                    ObservabilityResponse(
                        200,
                        """{"status":"success","data":{"resultType":"streams","result":[{"stream":{"cluster":"$cluster"},"values":$values}]}}""",
                    )
                }
                call.route == "/api/search" -> {
                    val cluster = call.param("q")!!.substringAfter("cluster = \"").substringBefore('"')
                    val ids = if (cluster in traced) """[{"traceID":"${"%032x".format(cluster.hashCode())}"}]""" else "[]"
                    ObservabilityResponse(200, """{"traces":$ids}""")
                }
                call.route.startsWith("/api/v2/traces/") -> {
                    if (failTraceFetch) return@FakeBackends ObservabilityResponse(500, "tempo broke")
                    val cluster = traced.first()
                    ObservabilityResponse(
                        200,
                        """{"trace":{"resourceSpans":[{"resource":{"attributes":[{"key":"cluster","value":{"stringValue":"$cluster"}}]},
                        "scopeSpans":[{"spans":[{"traceId":"0102030405060708090a0b0c0d0e0f10","spanId":"0102030405060708","name":"op"}]}]}]}}""",
                    )
                }
                else -> ObservabilityResponse(404, "no route ${call.route}")
            }
        }

    /** The writer: Loki and Tempo write into its store directory, as the real ones do. */
    private lateinit var writerDir: File
    private var pushes = 0
    private val writer =
        FakeBackends { call ->
            val store = File(writerDir, "store")
            when (call.route) {
                "/loki/api/v1/push" -> {
                    File(store, "loki/${call.tenant}/fp/chunk-${++pushes}").apply { parentFile.mkdirs() }.writeText(call.body)
                    File(store, "loki/index/index_1/${call.tenant}/file.tsdb.gz").apply { parentFile.mkdirs() }.writeText("index")
                    ObservabilityResponse(204, "")
                }
                "/ingester/shutdown" -> ObservabilityResponse(204, "")
                "/v1/traces" -> {
                    val block = File(store, "tempo/${call.tenant}/block-${++pushes}").apply { mkdirs() }
                    File(block, "meta.json").writeText("""{"blockID":"${block.name}","tenantID":"${call.tenant}"}""")
                    File(block, "data.parquet").writeText(call.body)
                    File(store, "tempo/${call.tenant}/index.json.gz").writeText("tempo's own index")
                    ObservabilityResponse(200, "{}")
                }
                "/metrics" -> ObservabilityResponse(200, "tempo_live_store_complete_queue_length 0\n")
                else -> ObservabilityResponse(404, "")
            }
        }

    private fun stack(
        name: String,
        directory: File,
        backends: FakeBackends,
    ): ExportStack =
        object : ExportStack {
            override val directory: File = directory

            override fun http(tenant: String): ObservabilityHttp = backends.forTenant(tenant)

            override fun stop(service: String) {
                stopped += "$name/$service"
            }

            override fun close() {
                closed += name
                // As DockerExportStack does: the project's logs are saved beside it before it is removed.
                File(directory, "compose.log").writeText("logs of $name")
            }
        }

    private val rewrites = mutableListOf<List<String>>()
    private var brokenRewrittenMeta = false
    private val runtime =
        object : QueryRuntime {
            override fun reader(
                work: QueryWorkDirectory,
                live: LocalStackSpec.Mode.Live,
            ): ExportStack = stack("reader", work.reader, reader)

            override fun writer(
                work: QueryWorkDirectory,
                home: String,
            ): ExportStack {
                writerDir = work.writer
                return stack("writer", work.writer, writer)
            }

            override fun rewrite(work: QueryWorkDirectory): BlockRewrite =
                BlockRewrite { tenantDir, tmpDir, blocks, keep ->
                    rewrites += keep
                    tmpDir.mkdirs()
                    blocks.forEach { source ->
                        File(tenantDir, "R$source/chunks").mkdirs()
                        File(tenantDir, "R$source/chunks/000001").writeText("kept")
                        File(tenantDir, "R$source/index").writeText("index")
                        File(tenantDir, "R$source/meta.json").writeText(
                            if (brokenRewrittenMeta) {
                                "not json"
                            } else {
                                """{"ulid":"R$source","minTime":1,"maxTime":2,"stats":{"numSeries":2},"thanos":{"labels":{},"source":"bucket.rewrite"}}"""
                            },
                        )
                    }
                }
        }

    private val dashboards =
        object : DashboardTreeSource {
            override val origin: String = "packaged dashboards"

            override fun render(context: DashboardInstallContext): Map<String, String> =
                mapOf(
                    "home/home.json" to
                        """{"tenants":"${context.tenants.all.joinToString(",")}","experiment":"${context.experiment?.pickerValue}"}""",
                )
        }

    private val exporter =
        QueryExporter(
            stores = { QueryStoreAccess(store, bucket = "acct", region = "eu-west-1", profile = "lab") },
            runtime = runtime,
            stack = ExportStackFiles(dashboards, toolVersion = "16"),
            zipWriter = ExportZipWriter(),
            eventBus = eventBus,
            clock = clock,
        )

    @BeforeEach
    fun seed() {
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
        listOf("acme", "default").forEach { tenant ->
            val min = now.minusSeconds(86_400).toEpochMilli()
            store.put("mimir/$tenant/01$tenant/meta.json", """{"ulid":"01$tenant","minTime":$min,"maxTime":${now.toEpochMilli()}}""")
            store.put("mimir/$tenant/01$tenant/index", "index")
            val block = """{"block_id":"01$tenant","min_time":$min,"max_time":${now.toEpochMilli()}}"""
            val index = """{"version":2,"blocks":[$block],"block_deletion_marks":[]}"""
            store.objects["mimir/$tenant/bucket-index.json.gz"] =
                ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(index.toByteArray()) } }.toByteArray() to now
        }
        store.put("grafana/annotations/acme/20261015-100000_$X1.json", "[x]")
        store.put("grafana/annotations/acme/20261015-100000_$Z3.json", "[z]")
        store.put("grafana/annotations/default/20261015-100000_$W4.json", "[w]")
        store.put("reports/acme/exp-a/index.html", "report a")
        store.put("reports/acme/exp-b/index.html", "report b")
        store.put("reports/acme/$OLD5/index.html", "report old")
        store.put("reports/default/exp-a/index.html", "report a of default")
    }

    private fun target(name: String = "out.zip") = ExportTarget.of(File(dir, name))

    private fun entries(zip: File): Map<String, String> =
        ZipFile(zip).use { file ->
            file.entries().asSequence().associate {
                it.name.substringAfter('/') to
                    file.getInputStream(it).readBytes().decodeToString()
            }
        }

    @Test
    fun `a cluster export holds only the selected clusters' signals, annotations and their experiments' reports`() {
        val target = target()

        exporter.export(target, ExportSelector.Clusters(listOf(X1)), workspace = null)

        val zip = entries(target.zip)
        assertThat(zip.keys.filter { it.startsWith("data/loki/") }).isNotEmpty().allMatch {
            it.startsWith("data/loki/acme/") ||
                it.startsWith("data/loki/index/")
        }
        assertThat(zip.filterKeys { it.startsWith("data/loki/acme/") }.values).allMatch { it.contains("line of $X1") }
        assertThat(zip.keys).contains("data/grafana/annotations/acme/20261015-100000_$X1.json", "data/reports/acme/exp-a/index.html")
        assertThat(zip.keys).doesNotContain(
            "data/grafana/annotations/acme/20261015-100000_$Z3.json",
            "data/reports/acme/exp-b/index.html",
            "data/reports/default/exp-a/index.html",
        )
        assertThat(zip.keys).anyMatch { it.startsWith("data/mimir/acme/R01acme/") }.contains("data/mimir/acme/bucket-index.json.gz")
        assertThat(rewrites).containsExactly(listOf(X1))
        // The zip's Tempo index is the export's own, of the blocks in it.
        assertThat(zip.getValue("data/tempo/acme/index.json.gz")).doesNotContain("tempo's own index")
        assertThat(zip.keys).anyMatch { it.matches(Regex("data/tempo/acme/block-\\d+/meta.json")) }
    }

    @Test
    fun `each signal is reported, a signal with nothing as zero, then the export and the kept work directory`() {
        traced = emptySet()
        val target = target()

        exporter.export(target, ExportSelector.Clusters(listOf(X1)), workspace = null)

        val signals = events.filterIsInstance<Event.Export.SignalExported>().associateBy { it.signal }
        assertThat(signals.keys).containsExactlyInAnyOrder("metrics", "logs", "traces")
        assertThat(signals.getValue("traces")).isEqualTo(Event.Export.SignalExported("traces", 0, 0))
        assertThat(signals.getValue("logs").items).isEqualTo(1)
        assertThat(signals.getValue("metrics").items).isEqualTo(1)
        val completed = events.filterIsInstance<Event.Export.QueryCompleted>().single()
        assertThat(completed.clusters).containsExactly(X1)
        assertThat(completed.toDisplayString()).contains("Profiles are not included", "#1016")
        val kept = events.filterIsInstance<Event.Export.WorkDirectoryKept>().single()
        assertThat(File(kept.path)).isDirectory()
        assertThat(kept.path).isEqualTo(File(dir, "out.zip.work-${kept.path.substringAfterLast("-")}").path)
        assertThat(kept.bytes).isPositive()
        assertThat(events.last()).isEqualTo(kept)
        assertThat(closed).containsExactlyInAnyOrder("reader", "writer")
        assertThat(stopped).containsExactly("writer/loki", "writer/tempo")
    }

    @Test
    fun `clusters of two tenants are exported together, the first one's tenant the home, every tenant in the stack`() {
        val target = target()

        exporter.export(target, ExportSelector.Clusters(listOf(X1, W4)), workspace = null)

        val zip = entries(target.zip)
        assertThat(zip.getValue("dashboards/current/home/home.json")).contains("\"tenants\":\"acme,default\"")
        assertThat(zip.keys).contains("data/grafana/annotations/default/20261015-100000_$W4.json", "data/reports/default/exp-a/index.html")
        assertThat(zip.keys).anyMatch { it.startsWith("data/mimir/default/") }
        val selected = events.filterIsInstance<Event.Export.ClustersSelected>().single()
        assertThat(selected.home).isEqualTo("acme")
        assertThat(selected.clusters.map { it.cluster to it.tenant }).containsExactly(X1 to "acme", W4 to "default")
    }

    @Test
    fun `an experiment export reads only its tenant, and covers its own report`() {
        val target = target()

        exporter.export(target, ExportSelector.Experiment(ExperimentKey("acme", "exp-a")), workspace = null)

        val zip = entries(target.zip)
        assertThat(
            events.filterIsInstance<Event.Export.ClustersSelected>().single().clusters.map {
                it.cluster
            },
        ).containsExactlyInAnyOrder(X1, Y2)
        assertThat(reader.calls.map { it.tenant }).containsOnly("acme")
        assertThat(zip.keys).contains("data/reports/acme/exp-a/index.html").doesNotContain("data/reports/default/exp-a/index.html")
        assertThat(zip.getValue("dashboards/current/home/home.json")).contains("acme/exp-a")
    }

    @Test
    fun `a pre-label cluster is its own experiment, and its report goes with it`() {
        val target = target()

        exporter.export(target, ExportSelector.Experiment(ExperimentKey("acme", OLD5)), workspace = null)

        assertThat(
            events
                .filterIsInstance<Event.Export.ClustersSelected>()
                .single()
                .clusters
                .map { it.cluster },
        ).containsExactly(OLD5)
        assertThat(entries(target.zip).keys).contains("data/reports/acme/$OLD5/index.html")
    }

    @Test
    fun `a cluster that does not exist fails naming it and the tenants searched, leaving no zip and keeping the work directory`() {
        val target = target()

        assertThatThrownBy { exporter.export(target, ExportSelector.Clusters(listOf(NOPE9)), workspace = null) }
            .isInstanceOf(CommandFailedException::class.java)

        val missing = events.filterIsInstance<Event.Export.ClusterNotFound>().single()
        assertThat(missing.cluster).isEqualTo(NOPE9)
        assertThat(missing.tenants).containsExactly("acme", "default")
        assertThat(target.zip).doesNotExist()
        assertThat(events.last()).isInstanceOf(Event.Export.WorkDirectoryKept::class.java)
        assertThat(closed).contains("reader")
    }

    @Test
    fun `an experiment with no clusters fails naming it, its tenant and the query`() {
        assertThatThrownBy { exporter.export(target(), ExportSelector.Experiment(ExperimentKey("acme", "ghost")), workspace = null) }
            .isInstanceOf(CommandFailedException::class.java)

        val missing = events.filterIsInstance<Event.Export.ExperimentNotFound>().single()
        assertThat(missing.experiment).isEqualTo("ghost")
        assertThat(missing.tenant).isEqualTo("acme")
        assertThat(missing.query).contains("up{experiment=\"ghost\"}")
    }

    @Test
    fun `a failure while copying traces leaves no zip and names the traces signal`() {
        failTraceFetch = true
        val target = target()

        assertThatThrownBy { exporter.export(target, ExportSelector.Clusters(listOf(X1)), workspace = null) }
            .isInstanceOf(CommandFailedException::class.java)

        val failed = events.filterIsInstance<Event.Export.Failed>().single()
        assertThat(failed.signal).isEqualTo("traces")
        assertThat(failed.reason).contains("tempo broke")
        assertThat(target.zip).doesNotExist()
        assertThat(dir.listFiles()!!.map { it.name }).noneMatch { it.contains(".partial-") }
        assertThat(closed).containsExactlyInAnyOrder("reader", "writer")
    }

    @Test
    fun `a failure names the temporary stacks' saved docker compose logs in the work directory`() {
        failTraceFetch = true

        assertThatThrownBy { exporter.export(target(), ExportSelector.Clusters(listOf(X1)), workspace = null) }
            .isInstanceOf(CommandFailedException::class.java)

        val work = File(events.filterIsInstance<Event.Export.WorkDirectoryKept>().single().path)
        val failed = events.filterIsInstance<Event.Export.Failed>().single()
        assertThat(failed.logs).containsExactly(File(work, "reader/compose.log").path, File(work, "writer/compose.log").path)
        assertThat(failed.toDisplayString()).contains(File(work, "reader/compose.log").path)
    }

    /** Runs one export expected to fail, and returns its Export.Failed. */
    private fun failedExport(selector: ExportSelector = ExportSelector.Clusters(listOf(X1))): Event.Export.Failed {
        val target = target()
        assertThatThrownBy { exporter.export(target, selector, workspace = null) }.isInstanceOf(CommandFailedException::class.java)
        assertThat(target.zip).doesNotExist()
        return events.filterIsInstance<Event.Export.Failed>().single()
    }

    @Test
    fun `a failed listing of the annotation backups is reported with the annotations signal and the listing`() {
        store.beforeList = { prefix -> if (prefix.startsWith("grafana/annotations/")) throw IOException("S3 refused the listing") }

        val failed = failedExport()

        assertThat(failed.signal).isEqualTo("annotations")
        assertThat(failed.step).contains("list grafana/annotations/acme/")
        assertThat(failed.reason).contains("S3 refused the listing")
    }

    @Test
    fun `a failed listing of an experiment's reports is reported with the reports signal and the listing`() {
        store.beforeList = { prefix -> if (prefix.startsWith("reports/")) throw IOException("S3 refused the listing") }

        val failed = failedExport()

        assertThat(failed.signal).isEqualTo("reports")
        assertThat(failed.step).contains("list reports/acme/exp-a/")
    }

    @Test
    fun `a rewritten block whose meta cannot be read is reported with the metrics signal and the clean-up step`() {
        brokenRewrittenMeta = true

        val failed = failedExport()

        assertThat(failed.signal).isEqualTo("metrics")
        assertThat(failed.step).contains("drop", "acme")
    }

    @Test
    fun `a work directory that cannot be made is reported with the stack signal`() {
        dir.setWritable(false)
        try {
            val failed = failedExport()

            assertThat(failed.signal).isEqualTo("stack")
            assertThat(failed.step).contains("work directory")
            assertThat(events.filterIsInstance<Event.Export.WorkDirectoryKept>()).isEmpty()
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun `nothing in the store is written`() {
        val before = store.objects.mapValues { it.value.first.toList() }

        exporter.export(target(), ExportSelector.Clusters(listOf(X1, W4)), workspace = null)

        assertThat(store.objects.mapValues { it.value.first.toList() }).isEqualTo(before)
    }

    @Test
    fun `inside the cluster's own workspace its tenant comes from the state`() {
        exporter.export(target(), ExportSelector.Clusters(listOf(X1)), WorkspaceCluster(X1, "acme"))

        assertThat(reader.calls.map { it.route }).doesNotContain("/prometheus/api/v1/series")
    }

    @Test
    fun `an experiment whose discovered cluster is not a cluster id fails naming it, and queries nothing with it`() {
        val target = target()

        assertThatThrownBy { exporter.export(target, ExportSelector.Experiment(ExperimentKey("acme", "exp-bad")), workspace = null) }
            .isInstanceOf(CommandFailedException::class.java)

        val refused = events.filterIsInstance<Event.Export.InvalidDiscoveredCluster>().single()
        assertThat(refused.experiment).isEqualTo("exp-bad")
        assertThat(refused.tenant).isEqualTo("acme")
        assertThat(refused.clusters).containsExactly(INJECTED)
        assertThat(refused.rule).isEqualTo(Constants.Observability.CLUSTER_ID_PATTERN)
        assertThat(reader.calls.map { it.route }).containsOnly("/prometheus/api/v1/label/cluster/values")
        assertThat(target.zip).doesNotExist()
    }

    private companion object {
        const val X1 = "x-00000000-0000-4000-8000-000000000001"
        const val Y2 = "y-00000000-0000-4000-8000-000000000002"
        const val Z3 = "z-00000000-0000-4000-8000-000000000003"
        const val W4 = "w-00000000-0000-4000-8000-000000000004"
        const val OLD5 = "old-00000000-0000-4000-8000-000000000005"
        const val NOPE9 = "nope-00000000-0000-4000-8000-000000000009"

        /** A `cluster` label value that would end a PromQL selector early. */
        const val INJECTED = "bad\"} or vector(1) #"
    }
}
