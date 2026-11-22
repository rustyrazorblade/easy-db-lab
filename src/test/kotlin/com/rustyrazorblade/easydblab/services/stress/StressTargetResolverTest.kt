package com.rustyrazorblade.easydblab.services.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.CassandraPodTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StressTargetResolverTest {
    @TempDir
    lateinit var workspace: File

    private val dbHost = ClusterHost("54.0.0.1", "10.0.1.6", "db0", "us-west-2a")

    private val cqlEndpoint =
        """
        - name: "CQL"
          node-type: db
          port: 30942
          type: native
        """.trimIndent()

    private fun state(
        dbHosts: List<ClusterHost> = emptyList(),
        runningKits: Set<String> = emptySet(),
        infrastructure: InfrastructureState? = InfrastructureState(vpcId = "vpc-1", region = "us-west-2"),
        namespace: String? = null,
    ) = ClusterState(
        name = "lab",
        initConfig = namespace?.let { InitConfig(region = "us-west-2", name = "lab", namespace = it) },
        hosts = if (dbHosts.isEmpty()) emptyMap() else mapOf(ServerType.Cassandra to dbHosts),
        infrastructure = infrastructure,
        runningKits = runningKits,
    )

    private fun installKit(
        resolvedArgs: String = "CASSANDRA_DC=east\n",
        namespace: String? = "lab",
        endpoints: String = cqlEndpoint,
        withConfig: Boolean = true,
        withService: Boolean = true,
    ): File =
        File(workspace, CassandraPodTarget.KIT).apply {
            mkdirs()
            File(this, Constants.Kit.RESOLVED_ARGS_FILE).writeText(resolvedArgs)
            if (withConfig) {
                File(this, Constants.Kit.CONFIG_FILE).writeText(
                    "name: cassandra-pods\ntype: db\nruntime:\n  type: pods\n" +
                        "  selector: \"easydblab/kit=cassandra-pods\"\n" +
                        namespace?.let { "  namespace: $it\n" }.orEmpty() +
                        "endpoints:\n" + endpoints.prependIndent("  ") + "\n",
                )
            }
            if (withService) {
                File(this, "nodeport-service.yaml").writeText(
                    """
                    apiVersion: v1
                    kind: Service
                    metadata:
                      name: cql-front
                      ${namespace?.let { "namespace: $it" }.orEmpty()}
                    spec:
                      type: NodePort
                      ports:
                        - name: cql
                          port: 31942
                          targetPort: 30942
                          nodePort: 30942
                    """.trimIndent(),
                )
            }
        }

    private val resolver = StressTargetResolver()

    private val kitRunning = setOf(CassandraPodTarget.KIT)

    @Test
    fun `a running Cassandra kit is targeted by its Service, its Service port and its datacenter`() {
        installKit()

        val target = resolver.resolve(state(runningKits = kitRunning), workspace)

        assertThat(target).isEqualTo(StressTarget("cql-front.lab.svc", 31942, "east"))
    }

    @Test
    fun `a kit and Service that name no namespace are targeted in the cluster namespace`() {
        installKit(namespace = null)

        val target = resolver.resolve(state(runningKits = kitRunning, namespace = "lab-a"), workspace)

        assertThat(target.contactPoint).isEqualTo("cql-front.lab-a.svc")
    }

    @Test
    fun `the running kit is preferred over VM db hosts`() {
        installKit()

        val target = resolver.resolve(state(listOf(dbHost), kitRunning), workspace)

        assertThat(target.contactPoint).isEqualTo("cql-front.lab.svc")
    }

    @Test
    fun `a kit installed but not running leaves the VM db hosts as the target, on the native port in the region`() {
        installKit()

        val target = resolver.resolve(state(listOf(dbHost)), workspace)

        assertThat(target).isEqualTo(StressTarget("10.0.1.6", Constants.Stress.DEFAULT_CASSANDRA_PORT, "us-west-2"))
    }

    @Test
    fun `a kit installed without a datacenter is in the kit's default datacenter`() {
        installKit(resolvedArgs = "CASSANDRA_VERSION=5.0\n")

        val target = resolver.resolve(state(runningKits = kitRunning), workspace)

        assertThat(target.datacenter).isEqualTo("dc1")
    }

    @Test
    fun `with neither a running kit nor VM db hosts, resolving fails naming both`() {
        assertThatThrownBy { resolver.resolve(state(), workspace) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(CassandraPodTarget.KIT)
            .hasMessageContaining("db hosts")
    }

    @Test
    fun `a running kit whose kit yaml is missing fails naming the missing file`() {
        val kitDir = installKit(withConfig = false)

        assertThatThrownBy { resolver.resolve(state(runningKits = kitRunning), workspace) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(File(kitDir, Constants.Kit.CONFIG_FILE).absolutePath)
    }

    @Test
    fun `a running kit that declares no native endpoint fails saying so`() {
        installKit(endpoints = "- name: \"UI\"\n  node-type: db\n  port: 30080\n  type: http")

        assertThatThrownBy { resolver.resolve(state(runningKits = kitRunning), workspace) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no native CQL endpoint")
    }

    @Test
    fun `a running kit whose CQL endpoint has no Service fails saying so, without falling back to a node`() {
        installKit(withService = false)

        assertThatThrownBy { resolver.resolve(state(listOf(dbHost), kitRunning), workspace) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no Service")
    }

    @Test
    fun `VM db hosts without a recorded region fail naming the region`() {
        assertThatThrownBy { resolver.resolve(state(listOf(dbHost), infrastructure = null), workspace) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Infrastructure state missing region. Re-provision the cluster to fix this.")
    }
}
