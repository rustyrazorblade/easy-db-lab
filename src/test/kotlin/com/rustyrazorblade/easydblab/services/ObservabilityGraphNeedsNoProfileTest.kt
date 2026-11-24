package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.di.KoinModules
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File

/**
 * Building the observability stack's services in a workspace with no AWS account must not build an
 * S3 client. The account compactor stage and the Grafana documents proxy need the account bucket's
 * region, but only on a deploy that has a bucket; building them must not reach the S3 client, whose
 * region comes from the profile, so a machine with no `settings.yaml` failed `grafana update-config`
 * with "settings.yaml not found" before it ran.
 *
 * Starts the production module list with only the [Context] replaced, so the profile directory has no
 * `settings.yaml` and any resolution of the profile throws.
 */
class ObservabilityGraphNeedsNoProfileTest {
    @TempDir
    lateinit var home: File

    @TempDir
    lateinit var workspace: File

    private fun startGraph(environment: Environment): Koin {
        val koin = startKoin { modules(KoinModules.getAllModules() + module { single { Context.forCli(home, workspace) } }) }.koin
        ClusterStateManager(File(workspace, CLUSTER_STATE)).replace(ClusterState(name = "local", environment = environment))
        return koin
    }

    @AfterEach
    fun stopGraph() {
        stopKoin()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("environmentsWithoutAws")
    fun `the observability stack service builds with no profile`(environment: Environment) {
        val koin = startGraph(environment)

        assertThatCode { koin.get<ObservabilityStackService>() }.doesNotThrowAnyException()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("environmentsWithoutAws")
    fun `the Grafana deploy service builds with no profile`(environment: Environment) {
        val koin = startGraph(environment)

        assertThatCode { koin.get<GrafanaDeployService>() }.doesNotThrowAnyException()
    }

    companion object {
        @JvmStatic
        fun environmentsWithoutAws(): List<Environment> =
            listOf(Environment.K3sDocker("/work/observability-data"), Environment.Kubeconfig("/home/me/.kube/config"))
    }
}
