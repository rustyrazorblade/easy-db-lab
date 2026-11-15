package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Presto's JMX agent attaches the JVM to itself, which sends it a SIGQUIT. The node's default
 * AppArmor profile refuses signals a container sends, so the coordinator and the workers crash at
 * startup unless their pods run unconfined. This pins the chart value that does that: the chart's
 * top-level `securityContext` is the pod security context of the coordinator and of the workers.
 * The real proof is a Presto start on a cluster; this keeps the value from being dropped.
 */
class PrestoAppArmorValuesTest : BaseKoinTest() {
    @Test
    fun `the chart values run the Presto pods without the node's AppArmor profile`() {
        val values =
            BuiltinKitFixture("presto", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
                .resource("values.yaml.template")

        val parsed = Serialization.unmarshal(values, Map::class.java)

        assertThat(parsed["securityContext"])
            .isEqualTo(mapOf("appArmorProfile" to mapOf("type" to "Unconfined")))
    }
}
