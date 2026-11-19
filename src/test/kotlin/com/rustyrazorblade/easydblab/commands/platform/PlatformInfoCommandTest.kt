package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.K8sService
import io.fabric8.kubernetes.api.model.PersistentVolume
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * `platform info` reads the kit PVs from the cluster only on local volumes, and prints why when the
 * listing fails instead of failing the command; on a provisioned class it never lists PVs.
 */
class PlatformInfoCommandTest : BaseKoinTest() {
    private val k8sService: K8sService = mock()
    private val clusterStateManager: ClusterStateManager = mock()
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { k8sService }
                single { clusterStateManager }
            },
        )

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    private fun state(kitStorageClass: String? = null) =
        ClusterState(
            name = "test",
            initConfig = InitConfig(kitStorageClass = kitStorageClass),
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(host("db0"), host("db1")),
                    ServerType.Stress to listOf(host("app0")),
                ),
        )

    private fun host(alias: String) = ClusterHost("54.0.0.1", "10.0.0.1", alias, "us-west-2a", "i-$alias")

    @Test
    fun `on local volumes it lists the kit PVs and prints the free ones per pool`() {
        whenever(clusterStateManager.load()).thenReturn(state())
        whenever(k8sService.listKitLocalPersistentVolumes())
            .thenReturn(Result.success(listOf(pv("db", "Available"), pv("db", "Available"), pv("db", "Bound"))))

        PlatformInfo().execute()

        assertThat(stdout.toString())
            .contains("DB nodes: 2  (nodeSelector: type=db, available PVs: 2)")
            .contains("App nodes: 1  (nodeSelector: type=app, available PVs: 0)")
    }

    @Test
    fun `on local volumes a failed PV listing is printed with its reason and the command still succeeds`() {
        whenever(clusterStateManager.load()).thenReturn(state())
        whenever(k8sService.listKitLocalPersistentVolumes()).thenReturn(Result.failure(IllegalStateException("forbidden")))

        PlatformInfo().execute()

        assertThat(stdout.toString()).contains("Available PVs: could not be read (forbidden)")
    }

    @Test
    fun `on a provisioned class it never lists PVs`() {
        whenever(clusterStateManager.load()).thenReturn(state(kitStorageClass = "retain-x"))

        PlatformInfo().execute()

        verify(k8sService, never()).listKitLocalPersistentVolumes()
        assertThat(stdout.toString()).contains("retain-x").doesNotContain("available PVs")
    }

    private fun pv(
        pool: String,
        phase: String,
    ): PersistentVolume =
        PersistentVolumeBuilder()
            .withNewSpec()
            .withNewNodeAffinity()
            .withNewRequired()
            .addNewNodeSelectorTerm()
            .addNewMatchExpression()
            .withKey(Constants.NODE_TYPE_LABEL)
            .withOperator("In")
            .withValues(pool)
            .endMatchExpression()
            .endNodeSelectorTerm()
            .endRequired()
            .endNodeAffinity()
            .endSpec()
            .withNewStatus()
            .withPhase(phase)
            .endStatus()
            .build()
}
