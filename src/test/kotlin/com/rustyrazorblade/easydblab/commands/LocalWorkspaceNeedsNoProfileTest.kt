package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.CommandLineParser
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.configuration.environment.StateClusterContractSource
import com.rustyrazorblade.easydblab.di.KoinModules
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.services.EnvironmentRequirements
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import picocli.CommandLine
import software.amazon.awssdk.regions.Region
import java.io.File
import java.lang.reflect.Field

/**
 * In a workspace with no AWS account (K3s in Docker, or a cluster the user brings) on a machine with
 * no easy-db-lab profile, no command the user can run there loads the AWS profile. It starts the
 * production module list with only the [Context] replaced, so the profile directory has no
 * `settings.yaml`, records the environment, walks the real command tree, and for every command the
 * executor would run there resolves every `by inject()` dependency the command declares.
 *
 * A command whose `@RequiresCapability` the environment lacks is not run (the executor reports
 * `CommandUnavailable` first), so it cannot reach the profile. Every other command is walked, those
 * that need an SSH key (`@RequireSSHKey`, such as a kit's `sql`) included.
 *
 * The rule for the rest: the profile is loaded only to build an AWS SDK client (the `Region`
 * binding), which only AWS-only services and the S3 store use. Anything else that reaches `User`
 * would make a local command ask for an AWS profile, so it fails here, a command's own `User` field
 * included. The one exception is [PROFILE_READ_ONLY_ON_AWS_PATH]: `init`, `up` and `down` hold their own
 * lazy `User` and read it only on the EC2 path, which `InitLocalTest`, `UpTest` and
 * `DownLocalWorkspaceTest` prove by running the local path with a `User` binding that throws. The
 * exemption covers only that direct field; one of their services that reached `User` still fails here.
 * The runtime reads of the profile's region (`ClusterState.region`, `ClusterContract.datacenter`) are
 * covered by `ClusterContractTest`: they never ask the profile for a state with no AWS account.
 */
class LocalWorkspaceNeedsNoProfileTest {
    @TempDir
    lateinit var home: File

    @TempDir
    lateinit var workspace: File

    private fun startGraph(environment: Environment) {
        startKoin { modules(KoinModules.getAllModules() + module { single { Context.forCli(home, workspace) } }) }
            .koin
            .let { check(it.getOrNull<Context>()?.workingDirectory == workspace) }
        ClusterStateManager(File(workspace, CLUSTER_STATE))
            .save(ClusterState(name = "local", versions = mutableMapOf(), environment = environment))
        installKit()
    }

    /** An installed kit with phase scripts and a SQL capability, so the walk covers the kit runner commands too. */
    private fun installKit() {
        val kit = File(workspace, KIT).also { File(it, "bin").mkdirs() }
        File(kit, "kit.yaml").writeText(
            """
            name: $KIT
            capabilities:
              - type: sql
                user: default
                driver-class: com.clickhouse.jdbc.ClickHouseDriver
            endpoints:
              - name: JDBC
                node-type: db
                port: 8123
                type: jdbc
                scheme: clickhouse
                path: /default
            """.trimIndent(),
        )
        listOf("start", "stop").forEach { File(kit, "bin/$it.sh").writeText("#!/bin/sh\n") }
    }

    @AfterEach
    fun stopGraph() {
        stopKoin()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("environmentsWithoutAws")
    fun `no command available in the workspace loads the profile except to build an AWS client`(environment: Environment) {
        startGraph(environment)
        assertThat(File(Context.forCli(home, workspace).profileDir, "settings.yaml")).doesNotExist()
        val requirements = EnvironmentRequirements(StateClusterContractSource(ClusterStateManager(File(workspace, CLUSTER_STATE))))

        val available =
            leafCommands(CommandLineParser().commandLine)
                .filter { (_, command) -> requirements.unavailable(command) == null }

        assertThat(
            available.map {
                it.first
            },
        ).describedAs(
            "the commands walked",
        ).contains("status", "down", "grafana update-config", "init", "$KIT start", "$KIT stop", "$KIT sql")
        val offenders = available.flatMap { (path, command) -> profileLoads(path, command) }
        assertThat(offenders).describedAs("dependencies that load the AWS profile, with the bindings that led there").isEmpty()
    }

    /** Every leaf of the command tree that the executor runs as a [PicoCommand], by its command path. */
    private fun leafCommands(line: CommandLine): List<Pair<String, PicoCommand>> {
        val children = line.subcommands.values.distinct()
        if (children.isEmpty()) {
            val command = line.getCommand<Any>() as? PicoCommand ?: return emptyList()
            return listOf(line.commandSpec.qualifiedName().substringAfter(' ') to command)
        }
        return children.flatMap(::leafCommands)
    }

    /** Each Koin-delegated property of [command] whose resolution loads `User` other than through an AWS client. */
    private fun profileLoads(
        path: String,
        command: PicoCommand,
    ): List<String> =
        injectedFields(command).mapNotNull { field ->
            field.isAccessible = true
            val failure = runCatching { (field.get(command) as Lazy<*>).value }.exceptionOrNull() ?: return@mapNotNull null
            val chain = bindingChain(failure)
            when {
                // A failure that never reached the profile (a missing Redis URL) is not this test's concern.
                User::class.java.name !in chain -> null
                Region::class.java.name in chain -> null
                chain == listOf(User::class.java.name) && path in PROFILE_READ_ONLY_ON_AWS_PATH -> null
                else -> "$path (${field.name.removeSuffix(DELEGATE)}): ${chain.joinToString(" > ") { it.substringAfterLast('.') }}"
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

    companion object {
        private const val DELEGATE = "\$delegate"
        private const val KIT = "workload"
        private val BINDING = Regex("'\\[(?:Singleton|Factory): '([^']+)'")

        /** Commands whose own `User` field is read only on the EC2 path, proven by their local-path tests. */
        private val PROFILE_READ_ONLY_ON_AWS_PATH = setOf("init", "up", "down")

        @JvmStatic
        fun environmentsWithoutAws(): List<Environment> =
            listOf(Environment.K3sDocker("/work/observability-data"), Environment.Kubeconfig("/home/me/.kube/config"))
    }
}
