package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.stream.Stream

/**
 * Every pod a built-in kit places is placed by its role's selector, never by a literal `type`
 * label: each placement renders the cluster's selector for its role (`__DB_NODE_SELECTOR__` or
 * `__APP_NODE_SELECTOR__`), so the nodes easy-db-lab builds get `type=<role>` and a cluster the
 * user brings with no mapping places the pods anywhere.
 */
class KitRolePlacementTest : BaseKoinTest() {
    private fun placements(
        kit: String,
        template: String,
        selectors: RoleSelectors,
    ): Placements {
        val fixture =
            BuiltinKitFixture(
                kit,
                TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()),
                roleSelectors = selectors,
            )
        val documents = Yaml().loadAll(fixture.renderText(template, EXTRA_ARGS[kit].orEmpty())).filterNotNull()
        return Placements().also { found -> documents.forEach { found.collect(it) } }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("placedTemplates")
    fun `each placement renders its role's mapped selector and no node affinity`(
        kit: String,
        template: String,
        roles: List<ServerType>,
    ) {
        val found = placements(kit, template, MAPPED)

        assertThat(found.selectors).isEqualTo(roles.map { MAPPED.of(it).labels })
        assertThat(found.nodeAffinities).`as`("node affinities").isZero()
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("placedTemplates")
    fun `on the nodes easy-db-lab builds each placement is the role's type label`(
        kit: String,
        template: String,
        roles: List<ServerType>,
    ) {
        assertThat(placements(kit, template, RoleSelectors.OWNED_NODES).selectors)
            .isEqualTo(roles.map { mapOf("type" to it.serverType) })
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("placedTemplates")
    fun `with no role mapped no placement constrains the node`(
        kit: String,
        template: String,
        roles: List<ServerType>,
    ) {
        val selectors = placements(kit, template, RoleSelectors.ANYWHERE).selectors
        when (kit) {
            // CNPG compares the Cluster's nodeSelector with its pod's, and the API server drops an
            // empty map from the pod: an empty selector there differs forever and restarts the
            // instance on every reconcile. An unconstrained Cluster names no nodeSelector at all.
            "postgres" -> assertThat(selectors).isEmpty()
            else -> assertThat(selectors).isEqualTo(roles.map { emptyMap<String, String>() })
        }
    }

    /**
     * The restart-loop regression: CNPG compares the Cluster's `nodeSelector` with its pod's, and the API
     * server drops an empty one from the pod, so any `nodeSelector` key in an unconstrained Cluster (an
     * empty map, a null) differs forever. The key itself must be absent, not merely empty.
     */
    @Test
    fun `an unconstrained postgres Cluster names no nodeSelector key at all`() {
        val found = placements("postgres", "postgres-cluster.yaml.template", RoleSelectors.ANYWHERE)

        assertThat(found.selectorKeys).`as`("nodeSelector keys").isZero()
        assertThat(found.affinities).containsExactly(emptyMap<String, Any>())
    }

    /** The `nodeSelector` maps of a rendered document, in document order, its node affinities and its keys. */
    private class Placements {
        val selectors = mutableListOf<Map<*, *>>()
        val affinities = mutableListOf<Any?>()
        var selectorKeys = 0
        var nodeAffinities = 0

        fun collect(node: Any?) {
            when (node) {
                is Map<*, *> ->
                    node.forEach { (key, value) ->
                        if (key == "nodeSelector") selectorKeys++
                        if (key == "affinity") affinities.add(value)
                        when {
                            key == "nodeSelector" && value is Map<*, *> -> selectors.add(value)
                            key == "nodeAffinity" -> nodeAffinities++
                            else -> collect(value)
                        }
                    }
                is List<*> -> node.forEach(::collect)
            }
        }
    }

    private companion object {
        val MAPPED =
            RoleSelectors.ANYWHERE.withMappings(
                RoleSelectors.parse(listOf("db=pool=databases", "app=pool=clients,zone=b")),
            )

        /** Values `kit install` derives rather than reads from arg defaults. */
        val EXTRA_ARGS =
            mapOf(
                "cassandra-pods" to mapOf("CASSANDRA_NODES" to "2"),
                "postgres" to
                    mapOf(
                        "IMAGE" to "ghcr.io/cloudnative-pg/postgresql:17",
                        "PG_MAJOR_VERSION" to "17",
                        "SHARED_PRELOAD_LIBRARIES" to "[]",
                        "POST_INIT_SQL" to "[]",
                        "POSTGRES_UID" to "26",
                        "POSTGRES_GID" to "26",
                    ),
            )

        private val DB = ServerType.Cassandra
        private val APP = ServerType.Stress

        @JvmStatic
        fun placedTemplates(): Stream<Arguments> =
            Stream.of(
                Arguments.of("cassandra-pods", "statefulset.yaml.template", listOf(DB)),
                Arguments.of("memcached", "memcached.yaml.template", listOf(DB)),
                Arguments.of("memcached", "memcached-extstore.yaml.template", listOf(DB)),
                Arguments.of("neo4j", "statefulset.yaml.template", listOf(DB)),
                Arguments.of("postgres", "postgres-cluster.yaml.template", listOf(DB)),
                Arguments.of("clickhouse", "clickhouseinstallation.yaml.template", listOf(DB)),
                Arguments.of("clickhouse", "clickhouse-keeper.yaml.template", listOf(DB)),
                Arguments.of("tidb", "tidbcluster.yaml.template", listOf(APP, APP, DB, DB)),
                Arguments.of("presto", "values.yaml.template", listOf(APP, APP)),
                Arguments.of("trino", "values.yaml.template", listOf(APP, APP)),
                Arguments.of("flink", "flinkdeployment.yaml.template", listOf(APP)),
            )
    }
}
