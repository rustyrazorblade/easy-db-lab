package com.rustyrazorblade.easydblab.configuration.tenancy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The ownership marks every easy-db-lab object carries: the two labels, the selector that finds
 * them, and the cluster-id suffix on cluster-scoped names.
 */
class OwnershipLabelsTest {
    @Test
    fun `labels name easy-db-lab and the cluster`() {
        assertThat(OwnershipLabels.of("3f2a"))
            .containsExactlyInAnyOrderEntriesOf(
                mapOf("app.kubernetes.io/managed-by" to "easy-db-lab", "easydblab/cluster" to "3f2a"),
            )
    }

    @Test
    fun `selector matches both labels`() {
        assertThat(OwnershipLabels.selector("3f2a"))
            .isEqualTo("app.kubernetes.io/managed-by=easy-db-lab,easydblab/cluster=3f2a")
    }

    @Test
    fun `cluster-scoped name ends in the full cluster id`() {
        assertThat(ClusterScopedName.of("otel-collector", "3f2a-77"))
            .isEqualTo("otel-collector-3f2a-77")
    }

    @Test
    fun `install scope takes the contract namespace and the state cluster id`() {
        val state =
            ClusterState(name = "c", clusterId = "id-1", initConfig = InitConfig())

        assertThat(InstallScope.of(state, ClusterContract.of(state)))
            .isEqualTo(InstallScope(K8sNamespace("default"), "id-1"))
    }
}
