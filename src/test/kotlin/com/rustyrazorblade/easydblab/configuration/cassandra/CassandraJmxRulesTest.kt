package com.rustyrazorblade.easydblab.configuration.cassandra

import com.charleskorn.kaml.Yaml
import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Guards the JMX Metric Insight rules the OTel Java agent reads on every Cassandra node.
 *
 * Nothing else can catch a mistake in this file before a cluster is provisioned. The agent reads it
 * inside the Cassandra JVM at startup, so a malformed document or a renamed key costs a whole bake
 * and provision cycle to discover, and shows up only as an empty panel.
 *
 * The file is self-contained — `otel.jmx.target.system` is not set — so it also carries the
 * families that used to come from the agent's built-in target. Owning those names is what protects
 * the dashboards from an agent upgrade, and it is why they are asserted here rather than trusted.
 */
class CassandraJmxRulesTest {
    @Serializable
    private data class JmxMapping(
        val metric: String,
        val desc: String = "",
        val type: String = "",
        val unit: String = "",
    )

    /**
     * The rule schema as JMX Metric Insight v2.31.1 defines it. Every key is optional there, so
     * every key is optional here; kaml is strict, and an unknown key fails the parse.
     */
    @Serializable
    private data class JmxRule(
        val bean: String = "",
        val beans: List<String> = emptyList(),
        val prefix: String = "",
        val handler: String = "",
        val type: String = "",
        val sourceUnit: String = "",
        val unit: String = "",
        val metricAttribute: Map<String, String> = emptyMap(),
        val mapping: Map<String, JmxMapping> = emptyMap(),
    )

    @Serializable
    private data class JmxRules(
        val rules: List<JmxRule>,
    )

    private val rules: JmxRules by lazy {
        val path = "$RESOURCE_DIR/${Constants.Cassandra.JMX_RULES_FILE}"
        val yaml =
            checkNotNull(javaClass.getResourceAsStream(path)) { "$path is not on the classpath" }
                .use { it.readBytes().decodeToString() }

        Yaml.default.decodeFromString(JmxRules.serializer(), yaml)
    }

    private val metricNames: List<String> by lazy { rules.rules.flatMap { rule -> rule.mapping.values.map { it.metric } } }

    private val latencyRule: JmxRule by lazy {
        rules.rules.single { rule -> rule.mapping.values.any { it.metric == "cassandra.client.request.latency.p99" } }
    }

    private val cassandraInSh: String by lazy { Files.readString(Paths.get("packer/cassandra/cassandra.in.sh")) }

    /**
     * Executable lines only. The file explains at length why it uses port 4318 and why it does not
     * select the built-in target, and those comments name the very strings the guards below forbid.
     * There are no trailing comments on code lines, so dropping whole-line comments is exact.
     */
    private val cassandraInShCode: String by lazy {
        cassandraInSh.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    }

    @Test
    fun `the rule file is valid YAML in the shape JMX Metric Insight reads`() {
        // Decoding is the assertion: kaml is strict, so an unknown key (metricAttributes, plural,
        // is the easy mistake) fails here rather than being ignored by the agent on a node.
        assertThat(rules.rules).isNotEmpty()
        assertThat(rules.rules).allSatisfy { rule ->
            assertThat(rule.bean + rule.beans.joinToString()).contains("org.apache.cassandra")
            // A rule either maps attributes or delegates to a code-based handler.
            assertThat(rule.mapping.isNotEmpty() || rule.handler.isNotEmpty()).isTrue()
        }
    }

    @Test
    fun `client request latency is reported in raw microseconds`() {
        // No sourceUnit means no conversion: the MBean value as Cassandra records it, the same
        // unit `nodetool proxyhistograms` prints, so the dashboard and the oracle are directly
        // comparable. The Prometheus suffix follows the unit, so changing either half of this
        // renames every latency series and empties the panels reading it. What an operator sees is
        // a Grafana display unit, set by a dashboard variable, never here.
        assertThat(latencyRule.unit).isEqualTo("us")
        assertThat(latencyRule.sourceUnit).isEmpty()
    }

    @Test
    fun `all four latency percentiles live in one rule, on the same five beans`() {
        // p999 used to be a rule of its own, and drifted from its siblings in three ways at once:
        // a different attribute name, an un-normalized value, and a wider bean pattern. Sharing a
        // rule is what makes a single panel able to plot p50, p99, p999 and max together, and is
        // what stops the three from ever separating again.
        val rule = latencyRule

        assertThat(rule.mapping.keys).containsExactlyInAnyOrder(
            "50thPercentile",
            "99thPercentile",
            "999thPercentile",
            "Max",
        )
        assertThat(rule.metricAttribute).containsEntry("cassandra.operation", "lowercase(param(scope))")

        // Enumerated, never scope=*: a wildcard also matches ViewWrite, the Accord scopes and one
        // Read-<CL>/Write-<CL> scope per consistency level, none of which is an operation.
        assertThat(rule.beans).containsExactlyInAnyOrder(
            "org.apache.cassandra.metrics:type=ClientRequest,scope=CASRead,name=Latency",
            "org.apache.cassandra.metrics:type=ClientRequest,scope=CASWrite,name=Latency",
            "org.apache.cassandra.metrics:type=ClientRequest,scope=RangeSlice,name=Latency",
            "org.apache.cassandra.metrics:type=ClientRequest,scope=Read,name=Latency",
            "org.apache.cassandra.metrics:type=ClientRequest,scope=Write,name=Latency",
        )
        assertThat(rule.bean).isEmpty()
    }

    @Test
    fun `no rule selects client requests with a wildcard scope`() {
        val clientRequestBeans =
            rules.rules.flatMap { it.beans + it.bean }.filter { it.contains("type=ClientRequest") }

        assertThat(clientRequestBeans).isNotEmpty()
        assertThat(clientRequestBeans).allSatisfy { assertThat(it).doesNotContain("scope=*") }
    }

    @Test
    fun `no rule emits a raw MBean scope where a normalized one is intended`() {
        // The p999 defect in one line: it carried param(scope) and produced operation="CASRead",
        // while its siblings produced cassandra_operation="rangeslice". Every attribute that names
        // a Cassandra operation or status must be normalized. The families below are the
        // deliberate exceptions - keyspace, table, thread-pool, verb, cache and buffer pool names
        // are all identifiers, and lowercasing them would merge distinct series or stop matching
        // what `nodetool` prints.
        val casePreserving =
            listOf("type=Table", "type=ThreadPools", "type=DroppedMessage", "type=Cache", "type=BufferPool")

        val offenders =
            rules.rules
                .filterNot { rule -> casePreserving.any { rule.bean.contains(it) } }
                .filter { rule -> rule.metricAttribute.values.any { it == "param(scope)" } }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the families the built-in target used to supply are all ported`() {
        // Dropping otel.jmx.target.system means these are no longer supplied by the agent. If a
        // port were missed, the metric would simply stop existing on the next bake.
        assertThat(metricNames).contains(
            "cassandra.compaction.tasks.completed",
            "cassandra.compaction.tasks.pending",
            "cassandra.storage.load",
            "cassandra.storage.hints.count",
            "cassandra.storage.hints.in_progress",
            "cassandra.client.request.latency.p50",
            "cassandra.client.request.latency.p99",
            "cassandra.client.request.latency.max",
            "cassandra.client.request.count",
            "cassandra.client.request.error",
        )
        // The compaction byte progress rule is code-based and has no mapping to check by name.
        assertThat(rules.rules).anySatisfy { rule ->
            assertThat(rule.handler).isEqualTo("cassandra-compaction-progress")
        }
    }

    @Test
    fun `the rules the built-in target never covered are present too`() {
        assertThat(metricNames).contains(
            "cassandra.client.request.latency.p999",
            "cassandra.table.disk.space.live",
            "cassandra.table.disk.space.total",
            "cassandra.table.sstable.count.live",
            "cassandra.table.compaction.pending",
            "cassandra.table.sstables.per.read.p99",
            "cassandra.thread_pool.tasks.active",
            "cassandra.thread_pool.tasks.pending",
            "cassandra.thread_pool.tasks.blocked",
            "cassandra.messages.dropped",
            // Every bean behind the families below was confirmed to exist by querying a live 5.0
            // node's MBean server. A bean name that does not exist matches nothing and emits
            // nothing, at no log level — so a typo here is invisible until a panel is empty.
            "cassandra.table.tombstones.scanned.p99",
            "cassandra.table.bloom_filter.false_ratio",
            "cassandra.table.bloom_filter.false_positives",
            "cassandra.cache.hit_ratio",
            "cassandra.cache.hits",
            "cassandra.cache.requests",
            "cassandra.cache.size",
            "cassandra.cache.entries",
            "cassandra.table.memtable.heap.size",
            "cassandra.table.memtable.offheap.size",
            "cassandra.table.memtable.live_data.size",
            "cassandra.table.memtable.columns",
            "cassandra.table.memtable.switches",
            "cassandra.table.memtable.bytes_flushed",
            "cassandra.commitlog.tasks.pending",
            "cassandra.commitlog.size",
            "cassandra.commitlog.waiting_on_commit.p99",
            "cassandra.commitlog.waiting_on_segment_allocation.p99",
            "cassandra.table.speculative.retries",
            "cassandra.streaming.incoming",
            "cassandra.streaming.outgoing",
            "cassandra.streaming.repair.outgoing",
            "cassandra.streaming.repair.sstables",
            "cassandra.streaming.active",
            "cassandra.repair.retries",
            "cassandra.messaging.cross_node.latency.p99",
            "cassandra.messaging.datacenter.latency.p99",
            // Read-path correctness and repair families. Each bean name is checked against the
            // Cassandra 6.0 source, cited next to its rule.
            "cassandra.table.range.latency.p99",
            "cassandra.table.live_rows.scanned.p99",
            "cassandra.table.read_repair.requests",
            "cassandra.table.short_read_protection.requests",
            "cassandra.table.replica_filtering_protection.requests",
            "cassandra.table.replica_filtering_protection.rows_cached.p99",
            "cassandra.table.repaired_data.inconsistencies.confirmed",
            "cassandra.table.repaired_data.inconsistencies.unconfirmed",
            "cassandra.table.repaired_data.overread.rows.p99",
            "cassandra.table.repaired_data.overread.time.p99",
            "cassandra.read_repair.blocking",
            "cassandra.read_repair.reconcile_reads",
            "cassandra.buffer_pool.hits",
            "cassandra.buffer_pool.misses",
            "cassandra.buffer_pool.capacity",
            "cassandra.buffer_pool.size",
            "cassandra.buffer_pool.used",
            "cassandra.buffer_pool.overflow",
            // Lightweight transaction families. Each bean name is checked against the Cassandra 6.0
            // source, cited next to its rule.
            "cassandra.client.request.cas.contention.p99",
            "cassandra.client.request.cas.contended",
            "cassandra.client.request.cas.unfinished_commits",
            "cassandra.client.request.cas.condition_not_met",
            "cassandra.paxos.linearizability_violations",
            "cassandra.client.request.consistency.latency.p99",
            "cassandra.table.cas.prepare.latency.p99",
            "cassandra.table.cas.propose.latency.p99",
            "cassandra.table.cas.commit.latency.p99",
        )
    }

    @Test
    fun `lightweight transactions report through the client request families`() {
        // CAS latency, request counts and errors use the same names as reads and writes, told apart
        // by cassandra_operation, so one panel can put a CAS write beside a regular write.
        val families =
            setOf(
                "cassandra.client.request.latency.p99",
                "cassandra.client.request.count",
                "cassandra.client.request.error",
            )
        val clientRequestRules = rules.rules.filter { rule -> rule.mapping.values.any { it.metric in families } }

        assertThat(clientRequestRules).hasSize(5)
        assertThat(clientRequestRules).allSatisfy { rule ->
            assertThat(rule.beans).anySatisfy { assertThat(it).contains("scope=CASRead,") }
            assertThat(rule.beans).anySatisfy { assertThat(it).contains("scope=CASWrite,") }
            assertThat(rule.metricAttribute).containsEntry("cassandra.operation", "lowercase(param(scope))")
        }
    }

    @Test
    fun `condition not met is read from the CASWrite scope only`() {
        // Cassandra registers the same counter again under scope=CASRead, as a pre-4.0 alias.
        // Matching both would report every CAS write whose condition failed twice.
        val rule =
            rules.rules.single { r -> r.mapping.values.any { it.metric == "cassandra.client.request.cas.condition_not_met" } }

        assertThat(rule.bean).isEqualTo("org.apache.cassandra.metrics:type=ClientRequest,scope=CASWrite,name=ConditionNotMet")
        assertThat(rule.beans).isEmpty()
    }

    @Test
    fun `CAS contention counts attempts, so it is not measured in microseconds`() {
        // ContentionHistogram is a plain Histogram of lost Paxos attempts per request. A `us` unit
        // would give it a time suffix and put it on a latency axis.
        val rule =
            rules.rules.single { r -> r.mapping.values.any { it.metric == "cassandra.client.request.cas.contention.p99" } }

        assertThat(rule.unit).isEqualTo("{attempt}")
        assertThat(rule.beans).containsExactlyInAnyOrder(
            "org.apache.cassandra.metrics:type=ClientRequest,scope=CASRead,name=ContentionHistogram",
            "org.apache.cassandra.metrics:type=ClientRequest,scope=CASWrite,name=ContentionHistogram",
        )
        assertThat(rule.mapping.getValue("Count").type).isEqualTo("counter")
    }

    @Test
    fun `the linearizability violation counter is the node wide Paxos bean`() {
        val rule =
            rules.rules.single { r -> r.mapping.values.any { it.metric == "cassandra.paxos.linearizability_violations" } }

        assertThat(rule.bean).isEqualTo("org.apache.cassandra.metrics:type=Paxos,name=LinearizabilityViolations")
        assertThat(rule.mapping.keys).containsExactly("Count")
        assertThat(rule.mapping.getValue("Count").type).isEqualTo("counter")
    }

    @Test
    fun `each consistency level latency rule names one operation at one level`() {
        // Read-QUORUM is what separates regular reads from serial reads, which plain scope=Read
        // also counts. Each rule reads one scope and names its operation and level as constants;
        // two rules giving the same pair would give one series two sources.
        val consistencyRules =
            rules.rules.filter { r -> r.mapping.values.any { it.metric == "cassandra.client.request.consistency.latency.p99" } }

        assertThat(consistencyRules.map { it.bean }).containsExactlyInAnyOrder(
            "org.apache.cassandra.metrics:type=ClientRequest,scope=Read-QUORUM,name=Latency",
            "org.apache.cassandra.metrics:type=ClientRequest,scope=Write-QUORUM,name=Latency",
        )
        assertThat(consistencyRules.map { it.metricAttribute }).containsExactlyInAnyOrder(
            mapOf("cassandra.operation" to "const(read)", "cassandra.consistency_level" to "const(quorum)"),
            mapOf("cassandra.operation" to "const(write)", "cassandra.consistency_level" to "const(quorum)"),
        )
        assertThat(consistencyRules).allSatisfy { rule ->
            assertThat(rule.mapping.keys).containsExactlyInAnyOrder(
                "50thPercentile",
                "99thPercentile",
                "999thPercentile",
                "Max",
            )
            assertThat(rule.unit).isEqualTo(latencyRule.unit)
            assertThat(rule.sourceUnit).isEmpty()
        }
    }

    @Test
    fun `row-count histograms are not measured in microseconds`() {
        // Like the tombstone histogram, these count rows per read, not time. A `us` unit would give
        // them a time suffix and put them on a latency axis.
        listOf(
            "cassandra.table.live_rows.scanned.p99",
            "cassandra.table.replica_filtering_protection.rows_cached.p99",
            "cassandra.table.repaired_data.overread.rows.p99",
        ).forEach { metric ->
            val rule = rules.rules.single { r -> r.mapping.values.any { it.metric == metric } }

            assertThat(rule.unit).describedAs(metric).isEqualTo("{row}")
        }
    }

    @Test
    fun `the repaired data overread timer is in microseconds like every other latency`() {
        // A plain Cassandra Timer reports in DEFAULT_TIMER_UNIT, microseconds, the same as the
        // LatencyMetrics timers, so it takes the same unit and no conversion.
        val rule =
            rules.rules.single { r -> r.mapping.values.any { it.metric == "cassandra.table.repaired_data.overread.time.p99" } }

        assertThat(rule.unit).isEqualTo(latencyRule.unit)
        assertThat(rule.sourceUnit).isEmpty()
        assertThat(rule.mapping.keys).containsExactlyInAnyOrder(
            "50thPercentile",
            "99thPercentile",
            "999thPercentile",
            "Max",
        )
    }

    @Test
    fun `buffer pool rules name the pool from the MBean scope`() {
        // Two pools share every bean name: chunk-cache and networking. Without the pool attribute
        // their sizes would collide into one series.
        val bufferPoolRules = rules.rules.filter { it.bean.contains("type=BufferPool") }

        assertThat(bufferPoolRules).hasSize(6)
        assertThat(bufferPoolRules).allSatisfy { rule ->
            assertThat(rule.bean).contains("scope=*")
            assertThat(rule.metricAttribute).containsEntry("pool", "param(scope)")
        }
    }

    @Test
    fun `the tombstone histogram counts objects, so it is not measured in microseconds`() {
        // TombstoneScannedHistogram reads like a latency and is not one: it is a plain Histogram of
        // tombstones per read, with no DurationUnit attribute on the MBean. Declaring it `us` would
        // give it a time suffix and put it on a latency axis.
        val rule =
            rules.rules.single { r -> r.mapping.values.any { it.metric == "cassandra.table.tombstones.scanned.p99" } }

        assertThat(rule.unit).isEqualTo("{tombstone}")
        assertThat(rule.mapping.keys).contains("999thPercentile", "Max")
    }

    @Test
    fun `the per-datacenter latency bean cannot sweep up the per-verb ones`() {
        // `name=*-Latency` is deliberately narrow. The ~79 per-verb beans are named
        // <VERB>-WaitLatency, which does not end in "-Latency", so they stay out. If this pattern
        // is ever widened, each node gains roughly 320 series in one step.
        val rule =
            rules.rules.single { r -> r.mapping.values.any { it.metric == "cassandra.messaging.datacenter.latency.p99" } }

        assertThat(rule.bean).endsWith("name=*-Latency")
        assertThat(rule.metricAttribute).containsEntry("datacenter", "param(name)")
        assertThat(rule.unit).isEqualTo("us")
    }

    @Test
    fun `per-table local latency is mapped for reads, writes, range reads and Paxos phases`() {
        // Coordinator latency alone cannot show network and coordination overhead: that is
        // coordinator minus local, and local is what these families supply. They carry the same
        // four percentiles and the same unit as the ClientRequest family so a panel can subtract
        // one from the other without relabelling anything.
        listOf("read", "write", "range", "cas.prepare", "cas.propose", "cas.commit").forEach { operation ->
            val rule =
                rules.rules.single { r -> r.mapping.values.any { it.metric == "cassandra.table.$operation.latency.p99" } }

            assertThat(rule.mapping.keys).containsExactlyInAnyOrder(
                "50thPercentile",
                "99thPercentile",
                "999thPercentile",
                "Max",
            )
            assertThat(rule.unit).isEqualTo(latencyRule.unit)
            assertThat(rule.sourceUnit).isEqualTo(latencyRule.sourceUnit)
            assertThat(rule.metricAttribute).containsEntry("keyspace", "param(keyspace)")
            assertThat(rule.metricAttribute).containsEntry("table", "param(scope)")
        }
    }

    @Test
    fun `cassandra_in_sh labels every metric with the build the node is running`() {
        // A mixed-version A/B is the normal case here, and which nodes hold which build changes
        // between runs. The label is what lets a dashboard group by variant instead of by host
        // name. It rides on otel.resource.attributes, so it lands on every metric the JVM exports,
        // which is what makes `sum by (cassandra_build)` work directly.
        assertThat(cassandraInShCode).contains("cassandra_build=")
        assertThat(cassandraInShCode).contains("-Dotel.resource.attributes=")

        val resourceAttributes = cassandraInShCode.substringAfter("-Dotel.resource.attributes=").substringBefore("\"")
        assertThat(resourceAttributes).contains("cassandra_build=")

        // Read from the symlink `cassandra use` moves, so a version switch on a subset of hosts
        // needs no re-push from the CLI: each node reports its own build on its next restart.
        assertThat(cassandraInShCode).contains("readlink -f /usr/local/cassandra/current")
    }

    @Test
    fun `cassandra_in_sh turns on both halves of the experimental runtime telemetry`() {
        // The JFR half is what supplies an allocation metric. Its plausible-looking alternative,
        // otel.instrumentation.runtime-telemetry-java17.enable-all, is a legacy name at agent
        // v2.31.1 and does nothing — a wrong flag here fails silently, emitting no error and no
        // metric, so the guard is that the two working names are present and stay present.
        assertThat(cassandraInShCode)
            .contains("-Dotel.instrumentation.runtime-telemetry.emit-experimental-jfr-metrics=true")
        assertThat(cassandraInShCode)
            .contains("-Dotel.instrumentation.runtime-telemetry.emit-experimental-telemetry=true")

        // Both ride on JVM_EXTRA_OPTS with every other agent flag, never on JVM_OPTS.
        assertThat(cassandraInShCode).doesNotContain("runtime-telemetry-java17")
    }

    @Test
    fun `only the error and consistency level families repeat a metric name`() {
        // Two rules emitting one name give the same series conflicting attribute sets. Two families
        // are the deliberate exceptions: three rules feed the error family, separated by
        // cassandra.status, and one rule per scope feeds the consistency level latency family,
        // separated by cassandra.operation and cassandra.consistency_level.
        val repeated =
            metricNames
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }

        assertThat(repeated).containsOnlyKeys(
            "cassandra.client.request.error",
            "cassandra.client.request.consistency.latency.p50",
            "cassandra.client.request.consistency.latency.p99",
            "cassandra.client.request.consistency.latency.p999",
            "cassandra.client.request.consistency.latency.max",
        )
    }

    @Test
    fun `every error rule carries a distinct status`() {
        val statuses =
            rules.rules
                .filter { rule -> rule.mapping.values.any { it.metric == "cassandra.client.request.error" } }
                .map { it.metricAttribute["cassandra.status"] }

        assertThat(statuses).containsExactlyInAnyOrder(
            "const(unavailable)",
            "const(timeout)",
            "const(failure)",
        )
    }

    @Test
    fun `per-table rules carry keyspace and table, not a keyspace-less rollup`() {
        val tableRules = rules.rules.filter { it.bean.contains("type=Table") }

        assertThat(tableRules).isNotEmpty()
        assertThat(tableRules).allSatisfy { rule ->
            // Not lowercased, unlike cassandra.operation: Cassandra identifiers are case-sensitive
            // when quoted, so folding case would merge two distinct tables into one series.
            assertThat(rule.metricAttribute).containsEntry("keyspace", "param(keyspace)")
            assertThat(rule.metricAttribute).containsEntry("table", "param(scope)")
        }
    }

    @Test
    fun `cassandra_in_sh points the agent at the path the CLI writes`() {
        // The node-side path is written in two places that never see each other: this Kotlin
        // constant, which decides where setup-instances puts the file, and the shell literal in
        // cassandra.in.sh, which decides where the agent looks for it. Drift between them is
        // silent - Cassandra starts, and no Cassandra metric is ever produced.
        assertThat(cassandraInSh).contains(Constants.Cassandra.JMX_RULES_PATH)
        assertThat(cassandraInSh).contains("-Dotel.jmx.config=")
    }

    @Test
    fun `cassandra_in_sh exports OTLP to the collector's HTTP port, not its gRPC port`() {
        // The agent's default protocol is http/protobuf. Sent at 4317, the collector's gRPC port,
        // every export fails with "HttpExporter - Failed to export" and no metric ever lands - a
        // failure visible only at runtime, on a provisioned cluster.
        assertThat(cassandraInShCode).contains("-Dotel.exporter.otlp.endpoint=http://localhost:4318")
        assertThat(cassandraInShCode).doesNotContain("4317")
    }

    @Test
    fun `cassandra_in_sh does not select the built-in target`() {
        // The built-in experimental-cassandra target hardcodes sourceUnit us / unit s, which is
        // what forced seconds. Selecting it again would also duplicate every ported rule above.
        assertThat(cassandraInShCode).doesNotContain("otel.jmx.target.system")
    }

    private companion object {
        const val RESOURCE_DIR = "/com/rustyrazorblade/easydblab/configuration/cassandra"
    }
}
