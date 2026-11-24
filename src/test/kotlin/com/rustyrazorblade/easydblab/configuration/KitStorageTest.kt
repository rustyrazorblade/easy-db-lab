package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.configuration.environment.Environment
import io.fabric8.kubernetes.api.model.Quantity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * A kit's claims take their StorageClass and default size from the environment, resolved once from
 * cluster state: owned nodes get per-kit local volumes, any other cluster a dynamic class (the
 * recorded one, else the cluster's default).
 */
class KitStorageTest {
    private fun stateWith(
        initConfig: InitConfig?,
        environment: Environment = Environment.Ec2K3s,
    ) = ClusterState(name = "test", initConfig = initConfig, environment = environment)

    /** Owned nodes never need the cluster's default class; asking for it is a bug. */
    private val noLookup: () -> String? = { error("the cluster's default class must not be asked for") }

    @Test
    fun `an EC2 cluster with no recorded kit class keeps its kits on local volumes of 10Ti`() {
        val storage = KitStorage.from(stateWith(InitConfig()), noLookup)

        assertThat(storage).isEqualTo(KitStorage.LocalVolumes(Quantity("10Ti")))
    }

    @Test
    fun `a state with no init configuration is on local volumes`() {
        assertThat(KitStorage.from(stateWith(null), noLookup)).isInstanceOf(KitStorage.LocalVolumes::class.java)
    }

    @Test
    fun `a recorded kit class is provisioned at the provisioned-class default size`() {
        val storage = KitStorage.from(stateWith(InitConfig(kitStorageClass = "retain-x")), noLookup)

        assertThat(storage).isEqualTo(KitStorage.Provisioned("retain-x", Quantity("100Gi")))
    }

    @Test
    fun `the init kit storage size overrides the default of either mode`() {
        val local = KitStorage.from(stateWith(InitConfig(kitStorageSize = "2Ti")), noLookup)
        val provisioned = KitStorage.from(stateWith(InitConfig(kitStorageClass = "retain-x", kitStorageSize = "200Gi")), noLookup)

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("notOwnedNodes")
    fun `a cluster whose nodes easy-db-lab does not own takes its recorded kit class`(environment: Environment) {
        val storage = KitStorage.from(stateWith(InitConfig(kitStorageClass = "retain-x"), environment), noLookup)

        assertThat(storage).isEqualTo(KitStorage.Provisioned("retain-x", Quantity("100Gi")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notOwnedNodes")
    fun `a cluster whose nodes easy-db-lab does not own, with no recorded class, takes the cluster's default`(environment: Environment) {
        val storage = KitStorage.from(stateWith(InitConfig(), environment)) { "local-path" }

        assertThat(storage).isEqualTo(KitStorage.Provisioned("local-path", Quantity("100Gi")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notOwnedNodes")
    fun `a cluster whose nodes easy-db-lab does not own, with no recorded and no default class, names no class`(environment: Environment) {
        val storage = KitStorage.from(stateWith(InitConfig(kitStorageSize = "5Gi"), environment)) { null }

        assertThat(storage).isEqualTo(KitStorage.NoStorageClass(Quantity("5Gi")))
        assertThat(storage.storageClassFor("postgres")).isEmpty()
    }

    /** The size is published to every kit's help text at startup, before the cluster is asked anything. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("notOwnedNodes")
    fun `the default size needs no class lookup`(environment: Environment) {
        assertThat(KitStorage.defaultSize(stateWith(InitConfig(), environment))).isEqualTo(Quantity("100Gi"))
        assertThat(KitStorage.defaultSize(stateWith(InitConfig(kitStorageSize = "5Gi"), environment))).isEqualTo(Quantity("5Gi"))
        assertThat(KitStorage.defaultSize(stateWith(InitConfig()))).isEqualTo(Quantity("10Ti"))
    }

    companion object {
        @JvmStatic
        fun notOwnedNodes(): List<Environment> = listOf(Environment.K3sDocker("/d"), Environment.Kubeconfig("/k"))
    }
}
