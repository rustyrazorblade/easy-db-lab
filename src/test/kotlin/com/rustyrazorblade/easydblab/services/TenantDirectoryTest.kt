package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which directories under `mimir/` count as tenants, read from an in-memory [ObjectStore] holding
 * one object in each directory.
 */
class TenantDirectoryTest {
    private val objectStore = InMemoryObjectStore()

    private fun directories(vararg keys: String) {
        keys.forEach { objectStore.uploadContent("x", ClusterStoragePath.fromKey("acct", "$it/meta.json")) }
    }

    @Test
    fun `tenant directories are kept, Mimir's own directory is dropped, the home tenant is added, and all are sorted`() {
        directories("mimir/zeta", "mimir/__mimir_cluster", "mimir/acme", "mimir/Bad.Name", "mimir/index")
        directories("loki/other")

        val tenants = TenantDirectory(objectStore).list("acct", home = "lab")

        assertThat(tenants).isEqualTo(TenantSet("lab", listOf("acme", "lab", "zeta")))
    }

    @Test
    fun `an empty store still yields the home tenant, once`() {
        assertThat(TenantDirectory(objectStore).list("acct", home = "default").all).containsExactly("default")
        assertThat(TenantDirectory.tenantsFrom(listOf("default"), "default").all).containsExactly("default")
    }
}
