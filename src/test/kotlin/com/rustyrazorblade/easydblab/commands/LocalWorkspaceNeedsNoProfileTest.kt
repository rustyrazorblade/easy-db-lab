package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaAnnotate
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaBackup
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaInstall
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaUpdateConfig
import com.rustyrazorblade.easydblab.commands.install.KitRunnerCommand
import com.rustyrazorblade.easydblab.commands.logs.LogsQuery
import com.rustyrazorblade.easydblab.commands.metrics.MetricsList
import com.rustyrazorblade.easydblab.commands.metrics.MetricsStream
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.di.KoinModules
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import software.amazon.awssdk.regions.Region
import java.io.File
import java.lang.reflect.Field

/**
 * In a K3s in Docker workspace on a machine with no easy-db-lab profile, the commands a local user
 * runs build their dependencies without loading the AWS profile. It starts the production module list
 * with only the [Context] replaced, so the profile directory has no `settings.yaml`, records a K3s in
 * Docker state, and resolves every `by inject()` dependency each command declares.
 *
 * The rule: the profile is loaded only to build an AWS SDK client (the `Region` binding), which only
 * AWS-only services and the S3 store use, or by a command's own `User` field (the AWS provisioning in
 * `up` and `down`). Anything else that reaches `User` (the SSH transport, the template variables)
 * would make a local kit, Grafana or logs command ask for an AWS profile, so it fails here.
 */
class LocalWorkspaceNeedsNoProfileTest {
    @TempDir
    lateinit var home: File

    @TempDir
    lateinit var workspace: File

    @BeforeEach
    fun startLocalGraph() {
        startKoin { modules(KoinModules.getAllModules() + module { single { Context.forCli(home, workspace) } }) }
            .koin
            .let { check(it.getOrNull<Context>()?.workingDirectory == workspace) }
        ClusterStateManager(File(workspace, CLUSTER_STATE))
            .save(ClusterState(name = "local", versions = mutableMapOf(), environment = Environment.K3sDocker(workspace.absolutePath)))
    }

    @AfterEach
    fun stopLocalGraph() {
        stopKoin()
    }

    @Test
    fun `local commands load the profile only for AWS clients`() {
        assertThat(File(Context.forCli(home, workspace).profileDir, "settings.yaml")).doesNotExist()
        val commands =
            listOf(
                Status(),
                KitRunnerCommand("clickhouse", File(workspace, "clickhouse"), "start"),
                Up(),
                Down(),
                GrafanaUpdateConfig(),
                GrafanaInstall(),
                GrafanaAnnotate(),
                GrafanaBackup(),
                LogsQuery(),
                MetricsStream(),
                MetricsList(),
            )

        val offenders = commands.flatMap(::profileLoadsOutsideAwsClients)

        assertThat(offenders).describedAs("dependencies that load the AWS profile, with the bindings that led there").isEmpty()
    }

    /** Each Koin-delegated property of [command] whose resolution loads `User` other than through an AWS client. */
    private fun profileLoadsOutsideAwsClients(command: PicoCommand): List<String> =
        injectedFields(command).mapNotNull { field ->
            field.isAccessible = true
            val failure = runCatching { (field.get(command) as Lazy<*>).value }.exceptionOrNull() ?: return@mapNotNull null
            val chain = bindingChain(failure)
            val name = "${command.javaClass.simpleName}.${field.name.removeSuffix(DELEGATE)}"
            when {
                // A failure that never reached the profile (a missing Redis URL) is not this test's concern.
                User::class.java.name !in chain -> null
                chain.firstOrNull() == User::class.java.name -> null
                Region::class.java.name in chain -> null
                else -> "$name: ${chain.joinToString(" > ") { it.substringAfterLast('.') }}: ${rootCause(failure)}"
            }
        }

    private fun injectedFields(command: PicoCommand): List<Field> =
        generateSequence<Class<*>>(command.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.name.endsWith(DELEGATE) && Lazy::class.java.isAssignableFrom(it.type) }
            .toList()

    /** The Koin definitions [failure] passed through, outermost first, by class name. */
    private fun bindingChain(failure: Throwable): List<String> =
        generateSequence(failure) { it.cause }
            .mapNotNull { BINDING.find(it.message.orEmpty())?.groupValues?.get(1) }
            .toList()

    private fun rootCause(failure: Throwable): String =
        generateSequence(failure) { it.cause }.last().let { "${it.javaClass.simpleName}: ${it.message}" }

    private companion object {
        const val DELEGATE = "\$delegate"
        val BINDING = Regex("'\\[(?:Singleton|Factory): '([^']+)'")
    }
}
