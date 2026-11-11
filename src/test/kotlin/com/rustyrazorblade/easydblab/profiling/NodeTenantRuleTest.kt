package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps the node reconciler's copy of the tenant-name rule the same as the CLI's.
 *
 * `init` checks the tenant against [Constants.Observability.TENANT_PATTERN]. The bash reconciler on
 * each node checks its own `TENANT_FORM` again, because it cannot share a process with the CLI. If the
 * two drift, a tenant `init` accepts is treated as an unreadable config on the node. The node then
 * ships chunks under the last good tenant or the default one, and the script tests still pass because
 * they only use a few fixed names.
 */
class NodeTenantRuleTest {
    @Test
    fun `the node reconciler accepts exactly the tenant names init accepts`() {
        val script = File("packer/cassandra/bin/edl-profiling-reconcile").readText()
        val nodeRule =
            requireNotNull(Regex("""^readonly TENANT_FORM='([^']*)'$""", RegexOption.MULTILINE).find(script)) {
                "edl-profiling-reconcile no longer declares TENANT_FORM"
            }.groupValues[1]

        assertThat(nodeRule).isEqualTo(Constants.Observability.TENANT_PATTERN)
    }

    @Test
    fun `the node reconciler refuses the tenant name init reserves`() {
        val script = File("packer/cassandra/bin/edl-profiling-reconcile").readText()
        val reserved =
            requireNotNull(Regex("""^readonly RESERVED_TENANT='([^']*)'$""", RegexOption.MULTILINE).find(script)) {
                "edl-profiling-reconcile no longer declares RESERVED_TENANT"
            }.groupValues[1]

        assertThat(reserved).isEqualTo(Constants.Observability.RESERVED_TENANT)
    }
}
