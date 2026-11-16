package com.rustyrazorblade.easydblab.configuration

import io.fabric8.kubernetes.api.model.Quantity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A kit's claims take their StorageClass and default size from the environment, resolved once from
 * cluster state: owned nodes get per-kit local volumes, any other cluster a recorded dynamic class.
 */
class KitStorageTest {
    private fun stateWith(initConfig: InitConfig?) =
        ClusterState(name = "test", versions = mutableMapOf(), initConfig = initConfig)

    @Test
    fun `a cluster with no recorded kit class keeps its kits on local volumes of 10Ti`() {
        val storage = KitStorage.from(stateWith(InitConfig()))

        assertThat(storage).isEqualTo(KitStorage.LocalVolumes(Quantity("10Ti")))
    }

    @Test
    fun `a state with no init configuration is on local volumes`() {
        assertThat(KitStorage.from(stateWith(null))).isInstanceOf(KitStorage.LocalVolumes::class.java)
    }

    @Test
    fun `a recorded kit class is provisioned at the provisioned-class default size`() {
        val storage = KitStorage.from(stateWith(InitConfig(kitStorageClass = "retain-x")))

        assertThat(storage).isEqualTo(KitStorage.Provisioned("retain-x", Quantity("100Gi")))
    }

    @Test
    fun `the init kit storage size overrides the default of either mode`() {
        val local = KitStorage.from(stateWith(InitConfig(kitStorageSize = "2Ti")))
        val provisioned = KitStorage.from(stateWith(InitConfig(kitStorageClass = "retain-x", kitStorageSize = "200Gi")))

        assertThat(local.defaultSize).isEqualTo(Quantity("2Ti"))
        assertThat(provisioned.defaultSize).isEqualTo(Quantity("200Gi"))
    }

    @Test
    fun `local volumes give each kit its own class`() {
        val storage = KitStorage.LocalVolumes(Quantity("10Ti"))

        assertThat(storage.storageClassFor("cassandra-pods")).isEqualTo("edl-local-cassandra-pods")
        assertThat(storage.storageClassFor("kafka")).isEqualTo("edl-local-kafka")
    }

    @Test
    fun `a provisioned class is the same for every kit`() {
        val storage = KitStorage.Provisioned("retain-x", Quantity("100Gi"))

        assertThat(storage.storageClassFor("cassandra-pods")).isEqualTo("retain-x")
        assertThat(storage.storageClassFor("kafka")).isEqualTo("retain-x")
    }
}
