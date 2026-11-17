package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KitPlacementVariablesTest {
    @Test
    fun `owned nodes publish one type label per role, as a JSON map for templates and a label selector for scripts`() {
        val vars = KitPlacementVariables(RoleSelectors.OWNED_NODES, dbNodeCount = 3, appNodeCount = 2).toMap()

        assertThat(vars).containsAllEntriesOf(
            mapOf(
                "DB_NODE_SELECTOR" to """{"type":"db"}""",
                "APP_NODE_SELECTOR" to """{"type":"app"}""",
                "DB_NODE_LABELS" to "type=db",
                "APP_NODE_LABELS" to "type=app",
                "CONTROL_NODE_LABELS" to "type=control",
                "DB_NODE_COUNT" to "3",
                "APP_NODE_COUNT" to "2",
            ),
        )
    }

    @Test
    fun `unconstrained roles publish an empty map and an empty label selector`() {
        val vars = KitPlacementVariables(RoleSelectors.ANYWHERE, dbNodeCount = 1, appNodeCount = 1).toMap()

        assertThat(vars["DB_NODE_SELECTOR"]).isEqualTo("{}")
        assertThat(vars["APP_NODE_SELECTOR"]).isEqualTo("{}")
        assertThat(vars["DB_NODE_LABELS"]).isEmpty()
        assertThat(vars["APP_NODE_LABELS"]).isEmpty()
        assertThat(vars["CONTROL_NODE_LABELS"]).isEmpty()
    }

    @Test
    fun `the db affinity names the selector only when the role is constrained`() {
        val anywhere = KitPlacementVariables(RoleSelectors.ANYWHERE, dbNodeCount = 1, appNodeCount = 1).toMap()
        val owned = KitPlacementVariables(RoleSelectors.OWNED_NODES, dbNodeCount = 1, appNodeCount = 1).toMap()

        assertThat(anywhere["DB_AFFINITY"]).isEqualTo("{}")
        assertThat(owned["DB_AFFINITY"]).isEqualTo("""{"nodeSelector":{"type":"db"}}""")
    }

    @Test
    fun `a mapped role publishes its own labels`() {
        val selectors = RoleSelectors.ANYWHERE.copy(db = NodeSelector(mapOf("pool" to "databases", "zone" to "a")))

        val vars = KitPlacementVariables(selectors, dbNodeCount = 2, appNodeCount = 4).toMap()

        assertThat(vars["DB_NODE_SELECTOR"]).isEqualTo("""{"pool":"databases","zone":"a"}""")
        assertThat(vars["DB_NODE_LABELS"]).isEqualTo("pool=databases,zone=a")
    }

    @Test
    fun `without the Kubernetes API, the recorded hosts stand in for the counts`() {
        val host = ClusterHost("1.1.1.1", "10.0.0.1", "db0", "a")
        val state =
            ClusterState(
                name = "c",
                hosts = mapOf(ServerType.Cassandra to listOf(host, host.copy(alias = "db1"))),
            )

        val placement = KitPlacementVariables.fromRecordedHosts(state)

        assertThat(placement.dbNodeCount).isEqualTo(2)
        assertThat(placement.appNodeCount).isZero()
        assertThat(placement.selectors).isEqualTo(RoleSelectors.OWNED_NODES)
    }
}
