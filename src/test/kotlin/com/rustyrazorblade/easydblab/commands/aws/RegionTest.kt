package com.rustyrazorblade.easydblab.commands.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class RegionTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> = listOf(module { single<ClusterStateManager> { mockClusterStateManager } })

    @BeforeEach
    fun setup() {
        mockClusterStateManager = mock()
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    @Test
    fun `execute outputs region from cluster state`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(name = "test-cluster", versions = mutableMapOf(), initConfig = InitConfig(region = "us-east-1")),
        )
        Region().execute()
        assertThat(stdout.toString().trim()).isEqualTo("us-east-1")
    }

    @Test
    fun `execute errors when no region configured`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(name = "test-cluster", versions = mutableMapOf(), initConfig = null),
        )
        assertThatThrownBy { Region().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No region configured")
    }
}
