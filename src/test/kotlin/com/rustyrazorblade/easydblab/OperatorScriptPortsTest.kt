package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Operator scripts under `bin/` run on the operator's machine, outside the cluster, so they reach a
 * backend on its published NodePort. A shell script cannot read [Constants], so the port is written
 * out there; this keeps it equal to the constant the Service is built with.
 */
class OperatorScriptPortsTest {
    private val projectDir =
        File(checkNotNull(System.getProperty("easydblab.projectDir")) { "easydblab.projectDir is not set; run the test through Gradle" })

    @Test
    fun `export-workload-metrics queries Mimir on the Mimir NodePort`() {
        val script = File(projectDir, "bin/export-workload-metrics").readText()

        assertThat(script).contains(":${Constants.K8s.MIMIR_NODE_PORT}/prometheus")
        assertThat(script).doesNotContain(":${Constants.K8s.MIMIR_HTTP_PORT}/")
    }
}
