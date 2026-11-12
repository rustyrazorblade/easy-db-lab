package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.providers.aws.awsClientsModule
import com.rustyrazorblade.easydblab.services.LokiQueryService
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import com.rustyrazorblade.easydblab.services.aws.awsServicesModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * The production [awsServicesModule] binding of the EMR log directory, resolved through Koin against a
 * [com.rustyrazorblade.easydblab.Context] whose workspace is not the process's current
 * directory. A bare relative `logs` in the binding would land under the process directory.
 */
internal class AwsModuleLogsDirTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            awsClientsModule,
            awsServicesModule,
            module {
                single<ClusterStateManager> {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(
                            ClusterState(name = "lab", versions = mutableMapOf(), s3Bucket = "bucket"),
                        )
                    }
                }
                single<ObjectStore> { mock() }
                single<LokiQueryService> { mock() }
            },
        )

    @Test
    fun `EMR logs are saved under the workspace, not the current directory`() {
        val logs = get<SparkService>().downloadAllLogs("s-1").getOrThrow()

        val expected = File(context.workingDirectory, Constants.EMR.LOCAL_LOGS_DIR).toPath()
        assertThat(logs).startsWith(expected)
        assertThat(logs.toFile()).isDirectory()
        assertThat(logs.isAbsolute).isTrue()
    }
}
