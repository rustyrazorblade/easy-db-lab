package com.rustyrazorblade.easydblab.services.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
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

    private fun state(
        dbHosts: List<ClusterHost> = emptyList(),
        runningKits: Set<String> = emptySet(),
    ) = ClusterState(
        name = "lab",
        hosts = if (dbHosts.isEmpty()) emptyMap() else mapOf(ServerType.Cassandra to dbHosts),
        infrastructure = InfrastructureState(vpcId = "vpc-1", region = "us-west-2"),
        runningKits = runningKits,
    )

    private fun installKit(
        resolvedArgs: String = "CASSANDRA_DC=east\n",
        namespace: String = "lab",
    ) {
        File(workspace, CassandraPodTarget.KIT).apply {
            mkdirs()
            File(this, Constants.Kit.RESOLVED_ARGS_FILE).writeText(resolvedArgs)
            File(this, Constants.Kit.CONFIG_FILE).writeText(
                """
                name: cassandra-pods
                type: db
                runtime:
                  type: pods
                  selector: "easydblab/kit=cassandra-pods"
                  namespace: $namespace
                endpoints:
                  - name: "CQL"
                    node-type: db
                    port: 30942
                    type: native
                """.trimIndent(),
            )
            File(this, "nodeport-service.yaml").writeText(
                """
                apiVersion: v1
                kind: Service
                metadata:
                  name: cql-front
                  namespace: $namespace
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

    @Test
    fun `a running Cassandra kit is targeted by its Service, its Service port and its datacenter`() {
        installKit()

        val target = resolver.resolve(state(runningKits = setOf(CassandraPodTarget.KIT)), workspace)

        assertThat(target).isEqualTo(StressTarget("cql-front.lab.svc", 31942, "east"))
    }

    @Test
    fun `the running kit is preferred over VM db hosts`() {
        installKit()

        val target = resolver.resolve(state(listOf(dbHost), setOf(CassandraPodTarget.KIT)), workspace)

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

        val target = resolver.resolve(state(runningKits = setOf(CassandraPodTarget.KIT)), workspace)

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
    fun `VM db hosts with no recorded region fail naming the fix`() {
        val noRegion = state(dbHosts = listOf(dbHost)).copy(infrastructure = null)

        assertThatThrownBy { resolver.resolve(noRegion, workspace) }
            .hasMessage("Infrastructure state missing region. Re-provision the cluster to fix this.")
    }
}
