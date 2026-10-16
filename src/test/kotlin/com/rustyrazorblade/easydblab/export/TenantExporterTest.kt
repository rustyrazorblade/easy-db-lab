package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.localstack.DashboardTreeSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

/**
 * A tenant export end to end, with the account bucket in memory: the size check before any copy,
 * the zip moved into place only when complete, every failure leaving no zip and naming the signal
 * and the step, and nothing in the store changed.
 */
class TenantExporterTest {
    @TempDir
    lateinit var dir: File

    private val store = FakeExportStore()
    private var free = Long.MAX_VALUE
    private val events = mutableListOf<Event>()
    private val eventBus = EventBus()

    /** Runs while the stack's files are rendered into the zip, after the partial file is open. */
    private var whileRendering: () -> Unit = {}

    private val dashboards =
        object : DashboardTreeSource {
            override val origin: String = "packaged dashboards"

            override fun render(context: DashboardInstallContext): Map<String, String> {
                whileRendering()
                return mapOf("home/home.json" to """{"uid":"home"}""")
            }
        }

    private fun exporter(zipWriter: ExportZipWriter = ExportZipWriter()) =
        TenantExporter(
            stores = { store },
            stack = ExportStackFiles(dashboards, toolVersion = "16"),
            zipWriter = zipWriter,
            diskSpace = { free },
            eventBus = eventBus,
        )

    private val exporter = exporter()

    @BeforeEach
    fun listen() {
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun seed() {
        store.put("tempo/acme/t1/data.parquet", "spans")
        store.put("tempo/acme/t1/meta.json", """{"blockID":"t1","tenantID":"acme"}""")
        store.put("mimir/acme/01A/meta.json", """{"ulid":"01A","minTime":1,"maxTime":2,"compaction":{"level":1},"thanos":{"labels":{}}}""")
        store.put("mimir/acme/01A/index", "index")
        val index = """{"version":2,"blocks":[{"block_id":"01A","min_time":1,"max_time":2}],"block_deletion_marks":[],"updated_at":1}"""
        store.objects["mimir/acme/bucket-index.json.gz"] =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(index.toByteArray()) } }.toByteArray() to
            Instant.parse("2026-10-15T10:00:00Z")
        store.put("loki/acme/fp/chunk", "logs")
        store.put("reports/acme/exp/index.html", "pointer")
    }

    private fun target() = ExportTarget.of(File(dir, "acme-export.zip"))

    private fun entries(zip: File): Set<String> =
        ZipFile(zip).use { file ->
            file
                .entries()
                .asSequence()
                .map { it.name }
                .toSet()
        }

    @Test
    fun `a tenant export holds the offline stack, the dashboards and the data, and says profiles are left out`() {
        seed()
        val target = target()

        exporter.export(target, tenant = "acme")

        assertThat(entries(target.zip)).contains(
            "acme-export/compose.yml",
            "acme-export/README.txt",
            "acme-export/dashboards/current/home/home.json",
            "acme-export/data/tempo/acme/t1/meta.json",
            "acme-export/data/tempo/acme/index.json.gz",
            "acme-export/data/mimir/acme/bucket-index.json.gz",
            // Loki chunks carry the name Loki's filesystem store gives them (see LokiFilesystemLayout).
            "acme-export/data/loki/acme/fp/Y2h1bms=",
            "acme-export/data/reports/acme/exp/index.html",
        )
        val completed = events.filterIsInstance<Event.Export.Completed>().single()
        assertThat(completed.excluded).containsExactly(Event.Export.Exclusion("profiles", 1016))
        assertThat(completed.toDisplayString()).contains("Profiles are not included", "#1016")
        assertThat(events.filterIsInstance<Event.Export.SizeEstimated>().single().bytes).isPositive()
        assertThat(dir.list()).containsExactly("acme-export.zip")
    }

    @Test
    fun `an export with no traces still holds the traces directory, which the offline Tempo cannot create on its read-only mount`() {
        seed()
        store.objects.keys.removeIf { it.startsWith("tempo/") }
        val target = target()

        exporter.export(target, tenant = "acme")

        val tempo =
            ZipFile(target.zip).use { file ->
                file
                    .entries()
                    .asSequence()
                    .filter { it.name == "acme-export/data/tempo/" }
                    .toList()
            }
        assertThat(tempo).singleElement().satisfies({ assertThat(it.isDirectory).isTrue() })
    }

    @Test
    fun `a tenant export with traces holds the traces directory once, beside its blocks`() {
        seed()
        val target = target()

        exporter.export(target, tenant = "acme")

        assertThat(entries(target.zip)).contains("acme-export/data/tempo/", "acme-export/data/tempo/acme/t1/meta.json")
    }

    @Test
    fun `nothing in the store is written, rewritten or removed`() {
        seed()
        val before = store.objects.mapValues { it.value.first.toList() to it.value.second }

        exporter.export(target(), tenant = "acme")

        assertThat(store.objects.mapValues { it.value.first.toList() to it.value.second }).isEqualTo(before)
    }

    @Test
    fun `short disk space fails before anything is copied, naming the size and the free space`() {
        seed()
        free = 10
        val target = target()

        assertThatThrownBy { exporter.export(target, tenant = "acme") }.isInstanceOf(CommandFailedException::class.java)

        val refused = events.filterIsInstance<Event.Export.InsufficientSpace>().single()
        assertThat(refused.freeBytes).isEqualTo(10)
        assertThat(refused.bytes).isGreaterThan(10)
        // Only the bucket index was read, to list the blocks; no object was copied.
        assertThat(store.opened).containsOnly("mimir/acme/bucket-index.json.gz")
        assertThat(dir.list()).isEmpty()
    }

    @Test
    fun `an empty tenant fails naming the tenant and the prefixes, and writes nothing`() {
        seed()

        assertThatThrownBy { exporter.export(target(), tenant = "ghost") }.isInstanceOf(CommandFailedException::class.java)

        val empty = events.filterIsInstance<Event.Export.EmptyTenant>().single()
        assertThat(empty.tenant).isEqualTo("ghost")
        assertThat(empty.prefixes).contains("mimir/ghost/", "tempo/ghost/", "loki/ghost/")
        assertThat(dir.list()).isEmpty()
    }

    @Test
    fun `a failure while copying traces leaves no zip, and the error names the traces signal and the step`() {
        seed()
        store.beforeOpen = { key -> if (key == "tempo/acme/t1/data.parquet") store.objects.remove(key) }
        val target = target()

        assertThatThrownBy { exporter.export(target, tenant = "acme") }.isInstanceOf(CommandFailedException::class.java)

        val failed = events.filterIsInstance<Event.Export.Failed>().single()
        assertThat(failed.signal).isEqualTo("traces")
        assertThat(failed.step).contains("t1")
        assertThat(failed.toDisplayString()).contains("traces", "No zip was left at ${target.zip.path}")
        assertThat(dir.list()).describedAs("no zip, no partial file, no scratch").isEmpty()
        assertThat(events.filterIsInstance<Event.Export.Completed>()).isEmpty()
    }

    /** Writes its partial file as `acme-export.zip.partial-x`, and cannot remove it. */
    private val stuckPartial = ExportZipWriter(partialSuffix = { "x" }, removePartial = { throw IOException("read-only directory") })

    @Test
    fun `a failed export whose partial file cannot be removed names the file left behind on screen`() {
        seed()
        store.beforeOpen = { key -> if (key == "tempo/acme/t1/data.parquet") store.objects.remove(key) }
        val target = target()
        val partial = File(dir, "acme-export.zip.partial-x").path

        assertThatThrownBy { exporter(stuckPartial).export(target, tenant = "acme") }.isInstanceOf(CommandFailedException::class.java)

        val failed = events.filterIsInstance<Event.Export.Failed>().single()
        assertThat(failed.signal).isEqualTo("traces")
        assertThat(failed.partialFile).isEqualTo(partial)
        assertThat(
            failed.toDisplayString(),
        ).contains("No zip was left at ${target.zip.path}", partial, "left behind", "read-only directory")
    }

    @Test
    fun `a complete zip whose partial file cannot be removed says the zip is in place and names the file left behind`() {
        seed()
        val target = target()
        val partial = File(dir, "acme-export.zip.partial-x").path

        assertThatThrownBy { exporter(stuckPartial).export(target, tenant = "acme") }.isInstanceOf(CommandFailedException::class.java)

        val failed = events.filterIsInstance<Event.Export.Failed>().single()
        assertThat(failed.partialFile).isEqualTo(partial)
        assertThat(failed.zipComplete).isTrue()
        assertThat(target.zip).isFile()
        assertThat(failed.toDisplayString())
            .contains("The zip is complete at ${target.zip.path}", partial, "left behind")
            .doesNotContain("No zip was left")
    }

    @Test
    fun `a file that appears at the path while the zip is written is reported with the stack signal and left as it is`() {
        seed()
        val target = target()
        whileRendering = { target.zip.writeText("someone else's") }

        assertThatThrownBy { exporter.export(target, tenant = "acme") }.isInstanceOf(CommandFailedException::class.java)

        val failed = events.filterIsInstance<Event.Export.Failed>().single()
        assertThat(failed.signal).isEqualTo("stack")
        assertThat(failed.step).contains("move")
        assertThat(failed.reason).contains("appeared while the export was written")
        assertThat(target.zip.readText()).isEqualTo("someone else's")
    }

    /**
     * The scratch directory is `<zip name>.scratch-<random digits>` beside the zip. A 245-character zip
     * name leaves room for the partial file (`.partial-x`, 255 characters, the longest file name
     * allowed) but not for the scratch directory, so only the scratch directory cannot be made.
     */
    @Test
    fun `a scratch directory that cannot be made is reported with the logs signal and the step`() {
        seed()
        val target = ExportTarget.of(File(dir, "a".repeat(241) + ".zip"))

        assertThatThrownBy { exporter(ExportZipWriter(partialSuffix = { "x" })).export(target, tenant = "acme") }
            .isInstanceOf(CommandFailedException::class.java)

        val failed = events.filterIsInstance<Event.Export.Failed>().single()
        assertThat(failed.signal).isEqualTo("logs")
        assertThat(failed.step).contains("scratch directory")
        assertThat(dir.list()).describedAs("no zip, no partial file").isEmpty()
    }
}
