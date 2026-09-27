package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Which directories under `mimir/` count as tenants. S3 is faked at the [ObjectStore] boundary,
 * answering a delimited listing with its common prefixes.
 */
class TenantDirectoryTest {
    private val objectStore = mock<ObjectStore>()

    private fun prefixes(vararg keys: String) {
        whenever(objectStore.listFiles(any(), eq(false), any()))
            .thenReturn(keys.map { ObjectStore.FileInfo(ClusterS3Path.fromKey("acct", it), 0, "") })
    }

    @Test
    fun `tenant directories are kept, Mimir's own directory is dropped, the home tenant is added, and all are sorted`() {
        prefixes("mimir/zeta/", "mimir/__mimir_cluster/", "mimir/acme/", "mimir/Bad.Name/", "mimir/index/")

        val tenants = TenantDirectory(objectStore).list("acct", home = "lab")

        assertThat(tenants).isEqualTo(TenantSet("lab", listOf("acme", "lab", "zeta")))
        val listed = argumentCaptor<ClusterS3Path>()
        verify(objectStore).listFiles(listed.capture(), eq(false), any())
        assertThat(listed.firstValue.getKey()).isEqualTo("mimir")
    }

    @Test
    fun `an empty store still yields the home tenant, once`() {
        prefixes()

        assertThat(TenantDirectory(objectStore).list("acct", home = "default").all).containsExactly("default")
        assertThat(TenantDirectory.tenantsFrom(listOf("default"), "default").all).containsExactly("default")
    }
}
