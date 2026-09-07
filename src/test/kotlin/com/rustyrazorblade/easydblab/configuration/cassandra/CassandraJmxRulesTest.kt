package com.rustyrazorblade.easydblab.configuration.cassandra

import com.charleskorn.kaml.Yaml
import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Guards the custom JMX Metric Insight rules the OTel Java agent reads on every Cassandra node.
 *
 * Nothing else can catch a mistake in this file before a cluster is provisioned. The agent reads it
 * inside the Cassandra JVM at startup, so a malformed document or a renamed key costs a whole bake
 * and provision cycle to discover, and shows up only as missing panels.
 */
class CassandraJmxRulesTest {
    @Serializable
    private data class JmxMapping(
        val metric: String,
        val desc: String,
    )

    @Serializable
    private data class JmxRule(
        val bean: String,
        val type: String,
        val unit: String,
        val metricAttribute: Map<String, String>,
        val mapping: Map<String, JmxMapping>,
    )

    @Serializable
    private data class JmxRules(
        val rules: List<JmxRule>,
    )

    private val rules: JmxRules by lazy {
        val yaml =
            checkNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)) {
                "$RESOURCE_PATH is not on the classpath"
            }.use { it.readBytes().decodeToString() }

        Yaml.default.decodeFromString(JmxRules.serializer(), yaml)
    }

    private fun metricNames(): List<String> = rules.rules.flatMap { rule -> rule.mapping.values.map { it.metric } }

    @Test
    fun `the rule file is valid YAML in the shape JMX Metric Insight reads`() {
        // Decoding is the assertion: kaml is strict, so an unknown key (metricAttributes, plural,
        // is the easy mistake) fails here rather than being ignored by the agent on a node.
        assertThat(rules.rules).isNotEmpty()
        assertThat(rules.rules).allSatisfy { rule ->
            assertThat(rule.bean).startsWith("org.apache.cassandra.metrics:")
            assertThat(rule.mapping).isNotEmpty()
        }
    }

    @Test
    fun `the rules cover every family the built-in experimental-cassandra target omits`() {
        // The built-in target supplies client-request p50, p99 and max and nothing else. Each name
        // below is a family a dashboard needs and that target does not emit.
        assertThat(metricNames()).contains(
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
        )
    }

    @Test
    fun `no metric name is defined twice`() {
        // Two rules emitting one name give the same series conflicting attribute sets, which the
        // collector accepts and the dashboard cannot untangle.
        assertThat(metricNames()).doesNotHaveDuplicates()
    }

    @Test
    fun `per-table rules carry keyspace and table, not a keyspace-less rollup`() {
        val tableRules = rules.rules.filter { it.bean.contains("type=Table") }

        assertThat(tableRules).isNotEmpty()
        assertThat(tableRules).allSatisfy { rule ->
            assertThat(rule.metricAttribute).containsEntry("keyspace", "param(keyspace)")
            assertThat(rule.metricAttribute).containsEntry("table", "param(scope)")
        }
    }

    @Test
    fun `cassandra_in_sh points the agent at the path the CLI writes`() {
        // The node-side path is written in two places that never see each other: this Kotlin
        // constant, which decides where setup-instances puts the file, and the shell literal in
        // cassandra.in.sh, which decides where the agent looks for it. Drift between them is
        // silent - Cassandra starts, and the custom families are simply absent.
        val script = Files.readString(Paths.get("packer/cassandra/cassandra.in.sh"))

        assertThat(script).contains(Constants.Cassandra.JMX_RULES_PATH)
        assertThat(script).contains("-Dotel.jmx.config=")
    }

    private companion object {
        const val RESOURCE_PATH =
            "/com/rustyrazorblade/easydblab/configuration/cassandra/${Constants.Cassandra.JMX_RULES_FILE}"
    }
}
