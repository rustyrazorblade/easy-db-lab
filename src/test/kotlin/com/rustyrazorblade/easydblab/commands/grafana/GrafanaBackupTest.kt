package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.AnnotationMirror
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationBackupResult
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationBackupService
import com.rustyrazorblade.easydblab.services.MirroredAnnotation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GrafanaBackupTest : BaseKoinTest() {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val steps = mutableListOf<String>()
    private var mirrorFailure: Throwable? = null

    private val mirror =
        object : AnnotationMirror {
            override fun push(annotation: MirroredAnnotation): Result<Unit> = Result.success(Unit)

            override fun syncAll(controlHost: ClusterHost): Result<Int> =
                mirrorFailure?.let { Result.failure(it) } ?: Result.success(3).also { steps.add("mirror") }
        }

    private val backups =
        object : GrafanaAnnotationBackupService {
            override fun backup(
                controlHost: ClusterHost,
                clusterState: ClusterState,
            ): Result<GrafanaAnnotationBackupResult> {
                steps.add("backup")
                return Result.success(GrafanaAnnotationBackupResult(ClusterS3Path.root("acct").resolve("x.json"), 3))
            }
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(
                            ClusterState(name = "lab", versions = mutableMapOf(), hosts = mapOf(ServerType.Control to listOf(control))),
                        )
                    }
                }
                single<AnnotationMirror> { mirror }
                single<GrafanaAnnotationBackupService> { backups }
            },
        )

    @Test
    fun `every annotation is mirrored to Loki before the JSON backup is taken`() {
        GrafanaBackup().execute()

        assertThat(steps).containsExactly("mirror", "backup")
    }

    @Test
    fun `a failed mirror fails the backup`() {
        mirrorFailure = IllegalStateException("Loki refused the push with status 503")

        assertThatThrownBy { GrafanaBackup().execute() }.hasMessageContaining("503")
    }
}
