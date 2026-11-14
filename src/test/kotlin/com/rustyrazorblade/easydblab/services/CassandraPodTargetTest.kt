package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.access.PodTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CassandraPodTargetTest {
    @TempDir
    lateinit var workspace: File

    private fun installKit(
        resolvedArgs: String,
        kitYaml: String? = null,
    ) {
        File(workspace, CassandraPodTarget.KIT).apply {
            mkdirs()
            File(this, Constants.Kit.RESOLVED_ARGS_FILE).writeText(resolvedArgs)
            kitYaml?.let { File(this, Constants.Kit.CONFIG_FILE).writeText(it) }
        }
    }

    @Test
    fun `the first StatefulSet pod on its native port, in the datacenter the kit was installed with`() {
        installKit("CASSANDRA_VERSION=5.0\nCASSANDRA_DC=east\n")

        val target = CassandraPodTarget.inWorkspace(workspace)

        assertThat(target.pod).isEqualTo(PodTarget("default", "cassandra-pods-0", Constants.Kit.CassandraPods.CQL_NODE_PORT))
        assertThat(target.datacenter).isEqualTo("east")
    }

    @Test
    fun `the pod is in the namespace the installed kit's runtime declares`() {
        installKit(
            "CASSANDRA_VERSION=5.0\n",
            kitYaml =
                """
                name: cassandra-pods
                runtime:
                  type: pods
                  selector: "easydblab/kit=cassandra-pods"
                  namespace: databases
                """.trimIndent(),
        )

        assertThat(CassandraPodTarget.inWorkspace(workspace).pod.namespace).isEqualTo("databases")
    }

    @Test
    fun `an install that recorded no datacenter uses the kit's default`() {
        installKit("CASSANDRA_VERSION=5.0\n")

        assertThat(CassandraPodTarget.inWorkspace(workspace).datacenter).isEqualTo("dc1")
    }

    @Test
    fun `a workspace without the kit fails, naming it`() {
        assertThatThrownBy { CassandraPodTarget.inWorkspace(workspace) }
            .hasMessageContaining("cassandra-pods")
    }
}
