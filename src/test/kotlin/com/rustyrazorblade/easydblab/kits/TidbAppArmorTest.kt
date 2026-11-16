package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * TiDB's PD start script runs `nslookup`. Ubuntu's host AppArmor profile for it, stacked with the
 * node's default container profile, refuses the signals nslookup's own threads send each other, so
 * nslookup segfaults and PD crash-loops. The PD container must run without the node's profile.
 * This pins the manifest value; the real proof is a `tidb start` on a cluster.
 */
class TidbAppArmorTest : BaseKoinTest() {
    private fun kit() = BuiltinKitFixture("tidb", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))

    private fun Map<*, *>.dig(vararg path: String): Any? = path.fold(this as Any?) { node, key -> (node as? Map<*, *>)?.get(key) }

    @Test
    fun `the tidb PD pod runs its container without the node's AppArmor profile`() {
        val cluster = Serialization.unmarshal(kit().renderText("tidbcluster.yaml.template"), Map::class.java)

        assertThat(cluster.dig("spec", "pd", "annotations", "container.apparmor.security.beta.kubernetes.io/pd")).isEqualTo("unconfined")
    }
}
