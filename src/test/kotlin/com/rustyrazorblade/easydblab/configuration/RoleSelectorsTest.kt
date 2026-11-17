package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class RoleSelectorsTest {
    private fun state(roleSelectors: RoleSelectors?) = ClusterState(name = "lab", roleSelectors = roleSelectors)

    @Test
    fun `a state that records no role selectors reads as the nodes easy-db-lab owns, one type label per role`() {
        val selectors = RoleSelectors.from(state(null))

        assertThat(selectors).isEqualTo(RoleSelectors.OWNED_NODES)
        assertThat(selectors.db.toLabelSelector()).isEqualTo("type=db")
        assertThat(selectors.app.toLabelSelector()).isEqualTo("type=app")
        assertThat(selectors.control.toLabelSelector()).isEqualTo("type=control")
    }

    @Test
    fun `anywhere constrains no role`() {
        val anywhere = RoleSelectors.ANYWHERE

        assertThat(listOf(anywhere.db, anywhere.app, anywhere.control)).allSatisfy { selector ->
            assertThat(selector.toJson()).isEqualTo("{}")
            assertThat(selector.toLabelSelector()).isEmpty()
            assertThat(selector.labels).isEmpty()
        }
    }

    @Test
    fun `a selector renders as a JSON map and a comma-separated label selector, keys sorted`() {
        val selector = NodeSelector(mapOf("zone" to "a", "pool" to "data\"bases"))

        assertThat(selector.toJson()).isEqualTo("""{"pool":"data\"bases","zone":"a"}""")
        assertThat(selector.toLabelSelector()).isEqualTo("pool=data\"bases,zone=a")
    }

    @Test
    fun `role mappings replace only the roles they name`() {
        val mapped =
            RoleSelectors.ANYWHERE.withMappings(
                RoleSelectors.parse(listOf("db=pool=databases", "app=pool=clients,zone=b")),
            )

        assertThat(mapped.db.labels).isEqualTo(mapOf("pool" to "databases"))
        assertThat(mapped.app.labels).isEqualTo(mapOf("pool" to "clients", "zone" to "b"))
        assertThat(mapped.control).isEqualTo(NodeSelector.NONE)
        assertThat(RoleSelectors.OWNED_NODES.withMappings(RoleSelectors.parse(listOf("app=pool=x"))).db)
            .isEqualTo(RoleSelectors.OWNED_NODES.db)
    }

    @ParameterizedTest
    @ValueSource(strings = ["db=bad", "db", "db==x", "db=k=", "db=k=v,", "stress=k=v", "db=k=v,bad"])
    fun `a malformed role mapping is refused, naming the value and the expected form`(value: String) {
        assertThatThrownBy { RoleSelectors.parse(listOf(value)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(value)
            .hasMessageContaining("<role>=<key=value>")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "db=-pool=x", "db=pool-=x", "db=po ol=x", "db=a/b/c=x", "db=/pool=x", "db=Exa_mple.com/pool=x",
            "db=pool=-x", "db=pool=x-", "db=pool=a b", "db=pool=a/b", "db=pool=a:b",
        ],
    )
    fun `a key or value Kubernetes would refuse as a label is refused at init, naming it`(value: String) {
        val label = value.substringAfter("=")

        assertThatThrownBy { RoleSelectors.parse(listOf(value)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(label)
            .hasMessageContaining("Kubernetes label")
    }

    @Test
    fun `a key or value longer than Kubernetes allows is refused`() {
        assertThatThrownBy { RoleSelectors.parse(listOf("db=${"k".repeat(64)}=x")) }
            .hasMessageContaining("Kubernetes label")
        assertThatThrownBy { RoleSelectors.parse(listOf("db=pool=${"v".repeat(64)}")) }
            .hasMessageContaining("Kubernetes label")
        assertThatThrownBy { RoleSelectors.parse(listOf("db=${"p".repeat(254)}/k=x")) }
            .hasMessageContaining("Kubernetes label")
    }

    @Test
    fun `prefixed keys and the full value alphabet are accepted`() {
        val mapped =
            RoleSelectors.parse(
                listOf("db=node.kubernetes.io/instance-type=m5.xlarge,topology.kubernetes.io/zone=us-west-2a,a_b=C-1.x_2"),
            )

        assertThat(mapped.getValue(ServerType.Cassandra).labels).isEqualTo(
            mapOf(
                "node.kubernetes.io/instance-type" to "m5.xlarge",
                "topology.kubernetes.io/zone" to "us-west-2a",
                "a_b" to "C-1.x_2",
            ),
        )
    }

    @Test
    fun `a role mapped to nothing is unconstrained`() {
        val mapped = RoleSelectors.OWNED_NODES.withMappings(RoleSelectors.parse(listOf("app=")))

        assertThat(mapped.app).isEqualTo(NodeSelector.NONE)
        assertThat(mapped.db).isEqualTo(RoleSelectors.OWNED_NODES.db)
    }

    @Test
    fun `the base selectors follow where the nodes come from`() {
        assertThat(RoleSelectors.base(NodeOrigin.OWNED)).isEqualTo(RoleSelectors.OWNED_NODES)
        assertThat(RoleSelectors.base(NodeOrigin.BROUGHT)).isEqualTo(RoleSelectors.ANYWHERE)
    }

    @Test
    fun `a role mapped twice is refused`() {
        assertThatThrownBy { RoleSelectors.parse(listOf("db=a=1", "db=b=2")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("db")
    }

    @Test
    fun `recorded role selectors survive a save and load`(
        @TempDir dir: File,
    ) {
        val manager = ClusterStateManager(File(dir, "state.json"))
        val mapped = RoleSelectors.ANYWHERE.withMappings(RoleSelectors.parse(listOf("db=pool=databases")))
        manager.save(state(mapped))

        assertThat(RoleSelectors.from(manager.load())).isEqualTo(mapped)
    }
}
