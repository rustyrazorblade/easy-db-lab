package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresCapability
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.BuildBaseImage
import com.rustyrazorblade.easydblab.commands.BuildCassandraImage
import com.rustyrazorblade.easydblab.commands.BuildImage
import com.rustyrazorblade.easydblab.commands.ConfigureAWS
import com.rustyrazorblade.easydblab.commands.Down
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.PruneAMIs
import com.rustyrazorblade.easydblab.commands.ShowIamPolicies
import com.rustyrazorblade.easydblab.commands.aws.Region
import com.rustyrazorblade.easydblab.commands.aws.S3Bucket
import com.rustyrazorblade.easydblab.commands.aws.Vpcs
import com.rustyrazorblade.easydblab.commands.cassandra.CassandraBuild
import com.rustyrazorblade.easydblab.commands.cassandra.CassandraInstall
import com.rustyrazorblade.easydblab.commands.cassandra.UseCassandra
import com.rustyrazorblade.easydblab.commands.ingress.IngressAllow
import com.rustyrazorblade.easydblab.commands.ingress.IngressRevoke
import com.rustyrazorblade.easydblab.commands.observability.CompactorStart
import com.rustyrazorblade.easydblab.commands.observability.CompactorStatus
import com.rustyrazorblade.easydblab.commands.observability.CompactorStop
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStart
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStatus
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStop
import com.rustyrazorblade.easydblab.commands.spark.SparkDown
import com.rustyrazorblade.easydblab.commands.spark.SparkInit
import com.rustyrazorblade.easydblab.commands.spark.SparkJobs
import com.rustyrazorblade.easydblab.commands.spark.SparkLogs
import com.rustyrazorblade.easydblab.commands.spark.SparkStatus
import com.rustyrazorblade.easydblab.commands.spark.SparkStop
import com.rustyrazorblade.easydblab.commands.spark.SparkSubmit
import com.rustyrazorblade.easydblab.commands.tailscale.TailscaleStart
import com.rustyrazorblade.easydblab.commands.tailscale.TailscaleStatus
import com.rustyrazorblade.easydblab.commands.tailscale.TailscaleStop
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.configuration.environment.StateClusterContractSource
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.DeclaresEnvironment
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.providers.docker.DockerErrorHandler
import com.rustyrazorblade.easydblab.proxy.DefaultProxyAvailability
import com.rustyrazorblade.easydblab.proxy.ProxyAvailability
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ProxyPreflight
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import picocli.CommandLine.Command
import java.io.File
import java.rmi.RemoteException

/**
 * Test suite for CommandExecutor.
 *
 * Tests the command lifecycle execution including:
 * - Immediate execution with execute { }
 * - Deferred execution with schedule { }
 * - Command chaining and order
 * - Post-success actions (@TriggerBackup)
 * - Failure handling in command chains
 */
class CommandExecutorTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockBackupRestoreService: BackupRestoreService
    private lateinit var mockUserConfigProvider: UserConfigProvider
    private lateinit var mockDockerClientProvider: DockerClientProvider
    private lateinit var mockResourceManager: ResourceManager
    private lateinit var mockSocksProxyService: SocksProxyService
    private lateinit var mockUserConfig: User
    private lateinit var proxyAvailability: ProxyAvailability
    private lateinit var commandExecutor: DefaultCommandExecutor

    // Track command execution order
    private val executionOrder = mutableListOf<String>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<BackupRestoreService> { mockBackupRestoreService }
                single<UserConfigProvider> { mockUserConfigProvider }
                single<DockerClientProvider> { mockDockerClientProvider }
                single<ResourceManager> { mockResourceManager }
                single<SocksProxyService> { mockSocksProxyService }
                single<User> { mockUserConfig }
                single { CommandFailureReporter(eventBus, listOf(DockerErrorHandler())) }
            },
        )

    private val emitted = mutableListOf<Event>()
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        emitted.add(envelope.event)
                    }

                    override fun close() {
                        // Nothing to release.
                    }
                },
            )
        }

    @BeforeEach
    fun setupMocks() {
        executionOrder.clear()
        mockClusterStateManager = mock()
        mockBackupRestoreService = mock()
        mockUserConfigProvider = mock()
        mockDockerClientProvider = mock()
        mockResourceManager = mock()
        mockSocksProxyService = mock()
        mockUserConfig = mock()
        proxyAvailability = DefaultProxyAvailability()

        // Default: profile is already set up
        whenever(mockUserConfigProvider.isSetup()).thenReturn(true)

        commandExecutor = executorWith(ProfileSetupCommandProvider { error("profile setup must not run here") })
    }

    private fun executorWith(profileSetupProvider: ProfileSetupCommandProvider) =
        DefaultCommandExecutor(
            context = context,
            clusterStateManager = mockClusterStateManager,
            requirementCheckDeps =
                RequirementCheckDeps(
                    userConfigProvider = mockUserConfigProvider,
                    dockerClientProvider = mockDockerClientProvider,
                    environmentRequirements = EnvironmentRequirements(StateClusterContractSource(mockClusterStateManager)),
                ),
            resourceManager = mockResourceManager,
            eventBus = eventBus,
            proxyPreflight = ProxyPreflight(mockClusterStateManager, mockSocksProxyService, ProxyEnvFile(context.workingDirectory)),
            proxyAvailability = proxyAvailability,
            profileSetupProvider = profileSetupProvider,
        )

    private fun recordEnvironment(environment: Environment) {
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load())
            .thenReturn(ClusterState(name = "test", versions = mutableMapOf(), environment = environment))
    }

    // ========== ENVIRONMENT REQUIREMENTS ==========

    @Test
    fun `an AWS-only command in a K3s in Docker workspace fails before running and before the profile check`() {
        recordEnvironment(Environment.K3sDocker("/data"))
        whenever(mockUserConfigProvider.isSetup()).thenReturn(false)
        var ran = false

        val exitCode = commandExecutor.executeTopLevel(AwsOnlyCommand { ran = true }, "cassandra use")

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(ran).isFalse()
        verify(mockUserConfigProvider, never()).isSetup()
        val unavailable = emitted.filterIsInstance<Event.Environment.CommandUnavailable>().single()
        assertThat(unavailable.command).isEqualTo("cassandra use")
        assertThat(unavailable.environment).isEqualTo("K3s in Docker")
        assertThat(unavailable.missing).containsExactly(NodeCapability.NODE_SSH, NodeCapability.AWS_ACCOUNT)
        assertThat(unavailable.alternative).isEqualTo("the cassandra-pods kit")
        assertThat(unavailable.toDisplayString()).contains("cassandra use", "K3s in Docker", "aws-account", "cassandra-pods")
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>()).isEmpty()
    }

    @Test
    fun `cassandra use in a K3s in Docker workspace names the environment and suggests the kit, with no SSH`() {
        recordEnvironment(Environment.K3sDocker("/data"))
        whenever(mockUserConfigProvider.isSetup()).thenReturn(false)

        val exitCode = commandExecutor.executeTopLevel(UseCassandra().apply { version = "5.0" }, "cassandra use")

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        val unavailable = emitted.filterIsInstance<Event.Environment.CommandUnavailable>().single()
        assertThat(unavailable.toDisplayString()).contains("cassandra use", "K3s in Docker", "aws-account", "cassandra-pods")
        // Its first act is to announce the switch, before any SSH; that it never did proves nothing ran.
        assertThat(emitted.filterIsInstance<Event.Cassandra.UsingVersion>()).isEmpty()
        verify(mockUserConfigProvider, never()).isSetup()
    }

    @Test
    fun `every spark subcommand in a K3s in Docker workspace fails fast naming the environment`() {
        recordEnvironment(Environment.K3sDocker("/data"))
        val commands = listOf(SparkInit(), SparkSubmit(), SparkStatus(), SparkJobs(), SparkLogs(), SparkStop(), SparkDown())

        val exitCodes = commands.map { commandExecutor.executeTopLevel(it) }

        assertThat(exitCodes).allMatch { it == Constants.ExitCodes.ERROR }
        assertThat(emitted.filterIsInstance<Event.Environment.CommandUnavailable>().map { it.environment })
            .hasSize(commands.size)
            .containsOnly("K3s in Docker")
    }

    /**
     * The spec's list of AWS-only commands, run through the executor in a K3s in Docker workspace with
     * no profile. Dropping `@RequiresCapability` from any one of them makes it run (or ask for a
     * profile) instead of failing fast, and this fails naming it.
     */
    @Test
    fun `every AWS-only command in a K3s in Docker workspace fails fast before the profile check`() {
        recordEnvironment(Environment.K3sDocker("/data"))
        whenever(mockUserConfigProvider.isSetup()).thenReturn(false)

        val refusedBeforeRunning =
            awsOnlyCommands().associate { factory ->
                val command = factory()
                emitted.clear()
                val exitCode = commandExecutor.executeTopLevel(command)
                val unavailable = emitted.filterIsInstance<Event.Environment.CommandUnavailable>().singleOrNull()
                command::class.simpleName to
                    (exitCode == Constants.ExitCodes.ERROR && unavailable?.environment == "K3s in Docker")
            }

        assertThat(refusedBeforeRunning.filterValues { !it }.keys).describedAs("AWS-only commands that ran").isEmpty()
        verify(mockUserConfigProvider, never()).isSetup()
    }

    /** The table above covers every command main code declares AWS-only, so a new one cannot be left out. */
    @Test
    fun `the AWS-only table names every command that requires aws-account`() {
        val declared =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.rustyrazorblade.easydblab.commands")
                .filter { it.isAnnotatedWith(RequiresCapability::class.java) }
                .filter { NodeCapability.AWS_ACCOUNT in it.reflect().getAnnotation(RequiresCapability::class.java).capabilities }
                .map { it.name }

        assertThat(awsOnlyCommands().map { it()::class.java.name }).containsExactlyInAnyOrderElementsOf(declared)
    }

    private fun awsOnlyCommands(): List<() -> PicoCommand> =
        listOf(
            { UseCassandra().apply { version = "5.0" } },
            ::CassandraInstall,
            ::BuildImage,
            ::BuildBaseImage,
            ::BuildCassandraImage,
            ::PruneAMIs,
            ::ShowIamPolicies,
            ::SparkInit,
            ::SparkSubmit,
            ::SparkStatus,
            ::SparkJobs,
            ::SparkLogs,
            ::SparkStop,
            ::SparkDown,
            ::OpenSearchStart,
            ::OpenSearchStatus,
            ::OpenSearchStop,
            ::TailscaleStart,
            ::TailscaleStatus,
            ::TailscaleStop,
            ::IngressAllow,
            ::IngressRevoke,
            ::Region,
            ::S3Bucket,
            ::Vpcs,
            ::CompactorStart,
            ::CompactorStop,
            ::CompactorStatus,
            ::ConfigureAWS,
            { CassandraBuild().apply { javaVersion = "21" } },
        )

    @Test
    fun `an AWS-only command outside a workspace passes the capability check`() {
        var ran = false

        val exitCode = commandExecutor.executeTopLevel(AwsOnlyCommand { ran = true })

        assertThat(exitCode).isZero()
        assertThat(ran).isTrue()
        assertThat(emitted.filterIsInstance<Event.Environment.CommandUnavailable>()).isEmpty()
    }

    @Test
    fun `a profile command in a K3s in Docker workspace runs with no profile and no setup`() {
        recordEnvironment(Environment.K3sDocker("/data"))
        whenever(mockUserConfigProvider.isSetup()).thenReturn(false)
        var ran = false

        val exitCode = commandExecutor.execute { ProfileRequiringCommand { ran = true } }

        assertThat(exitCode).isZero()
        assertThat(ran).isTrue()
    }

    @Test
    fun `a command declaring a local environment needs no profile even in a workspace recorded as EC2`() {
        recordEnvironment(Environment.Ec2K3s)
        whenever(mockUserConfigProvider.isSetup()).thenReturn(false)
        var ran = false

        val exitCode = commandExecutor.execute { DeclaringCommand(Environment.K3sDocker("/data")) { ran = true } }

        assertThat(exitCode).isZero()
        assertThat(ran).isTrue()
    }

    // ========== EXECUTE IMMEDIATE TESTS ==========

    @Test
    fun `execute should run command immediately and return exit code`() {
        // When
        val exitCode =
            commandExecutor.execute {
                TestCommand { executionOrder.add("command1") }
            }

        // Then
        assertThat(exitCode).isEqualTo(0)
        assertThat(executionOrder).containsExactly("command1")
    }

    @Test
    fun `execute should return non-zero exit code on failure`() {
        // When
        val exitCode =
            commandExecutor.execute {
                FailingCommand()
            }

        // Then
        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
    }

    @Test
    fun `a command that already reported its failure exits non-zero without a second error report`() {
        val exitCode = commandExecutor.execute { ReportedFailureCommand() }

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>()).isEmpty()
    }

    @Test
    fun `a failure in top-level dispatch outside the lifecycle is reported once and not thrown`() {
        whenever(mockResourceManager.closeAll()).thenThrow(IllegalStateException("close failed"))

        val exitCode = commandExecutor.executeTopLevel(TestCommand())

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>().single().error)
            .contains("close failed")
    }

    @Test
    fun `an unexpected failure is reported as an execution error`() {
        commandExecutor.execute { FailingCommand() }

        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>()).hasSize(1)
    }

    @Test
    fun `a missing cluster state is reported as an execution error naming the absolute path`() {
        val missing = context.workspaceFile("no-such-state.json")

        val exitCode = commandExecutor.execute { MissingStateCommand(missing) }

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>().single().error)
            .contains("IllegalStateException")
            .contains(missing.absolutePath)
    }

    @Test
    fun `a docker failure from a command is reported as unavailable with the check-docker remedy`() {
        val exitCode =
            commandExecutor.execute {
                ThrowingCommand(
                    com.rustyrazorblade.easydblab.DockerException(
                        "Error pulling image x:1",
                        java.net.ConnectException("Connection refused"),
                    ),
                )
            }

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        val event = emitted.filterIsInstance<Event.Command.ProviderError>().single()
        assertThat(event.kind).isEqualTo(ProviderErrorKind.UNAVAILABLE)
        assertThat(event.toDisplayString()).contains("Docker is running")
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>()).isEmpty()
    }

    @Test
    fun `a docker 404 keeps its own message and gets no check-docker remedy`() {
        commandExecutor.execute {
            ThrowingCommand(
                com.rustyrazorblade.easydblab.DockerException(
                    "Failed to start container abc",
                    com.github.dockerjava.api.exception
                        .NotFoundException("No such container: abc"),
                ),
            )
        }

        assertThat(emitted.filterIsInstance<Event.Command.ProviderError>()).isEmpty()
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>().single().error)
            .contains("No such container: abc")
            .doesNotContain("Docker is running")
    }

    @Test
    fun `an illegal argument shows its message in the execution error`() {
        commandExecutor.execute { ThrowingCommand(IllegalArgumentException("bad node count 0")) }

        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>().single().error)
            .contains("IllegalArgumentException")
            .contains("bad node count 0")
    }

    @Test
    fun `an illegal state shows its message in the execution error`() {
        commandExecutor.execute { ThrowingCommand(IllegalStateException("cluster is down")) }

        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>().single().error)
            .contains("IllegalStateException")
            .contains("cluster is down")
    }

    @Test
    fun `a remote failure shows its message in the execution error`() {
        commandExecutor.execute { ThrowingCommand(RemoteException("Remote command failed (1): ls")) }

        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>().single().error)
            .contains("RemoteException")
            .contains("Remote command failed (1): ls")
    }

    @Test
    fun `an Error in top-level dispatch is rethrown, not reported as an execution error`() {
        whenever(mockResourceManager.closeAll()).thenThrow(OutOfMemoryError("heap"))

        assertThatThrownBy { commandExecutor.executeTopLevel(TestCommand()) }
            .isInstanceOf(OutOfMemoryError::class.java)
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>()).isEmpty()
    }

    @Test
    fun `an interrupt in top-level dispatch keeps the interrupt flag and is not reported`() {
        // Kotlin does not declare checked exceptions, so Mockito needs an answer to throw one.
        whenever(mockResourceManager.closeAll()).thenAnswer { throw InterruptedException("stopped") }

        try {
            assertThatThrownBy { commandExecutor.executeTopLevel(TestCommand()) }
                .isInstanceOf(InterruptedException::class.java)

            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>()).isEmpty()
    }

    @Test
    fun `an interrupt inside a command keeps the interrupt flag and is not reported`() {
        try {
            assertThatThrownBy { commandExecutor.execute { ThrowingCommand(InterruptedException("stopped")) } }
                .isInstanceOf(InterruptedException::class.java)

            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
        assertThat(emitted.filterIsInstance<Event.Command.ExecutionError>()).isEmpty()
    }

    // ========== SCHEDULE DEFERRED TESTS ==========

    @Test
    fun `schedule should defer command execution until after current command lifecycle`() {
        // Given - a command that schedules another command
        val parentCommand =
            TestCommand {
                executionOrder.add("parent_before_schedule")
                commandExecutor.schedule {
                    TestCommand { executionOrder.add("scheduled") }
                }
                executionOrder.add("parent_after_schedule")
            }

        // When
        val exitCode = commandExecutor.executeTopLevel(parentCommand)

        // Then
        assertThat(exitCode).isEqualTo(0)
        // Parent should complete fully before scheduled command runs
        assertThat(executionOrder).containsExactly(
            "parent_before_schedule",
            "parent_after_schedule",
            "scheduled",
        )
    }

    @Test
    fun `multiple scheduled commands should run in order`() {
        // Given - a command that schedules multiple commands
        val parentCommand =
            TestCommand {
                executionOrder.add("parent")
                commandExecutor.schedule {
                    TestCommand { executionOrder.add("first") }
                }
                commandExecutor.schedule {
                    TestCommand { executionOrder.add("second") }
                }
                commandExecutor.schedule {
                    TestCommand { executionOrder.add("third") }
                }
            }

        // When
        val exitCode = commandExecutor.executeTopLevel(parentCommand)

        // Then
        assertThat(exitCode).isEqualTo(0)
        assertThat(executionOrder).containsExactly("parent", "first", "second", "third")
    }

    @Test
    fun `failure in scheduled command should stop chain and return error code`() {
        // Given - a command that schedules a failing command followed by another
        val parentCommand =
            TestCommand {
                executionOrder.add("parent")
                commandExecutor.schedule {
                    TestCommand { executionOrder.add("first") }
                }
                commandExecutor.schedule {
                    FailingCommand().also { executionOrder.add("failing") }
                }
                commandExecutor.schedule {
                    TestCommand { executionOrder.add("should_not_run") }
                }
            }

        // When
        val exitCode = commandExecutor.executeTopLevel(parentCommand)

        // Then
        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        // Third command should not run after failure
        assertThat(executionOrder).containsExactly("parent", "first", "failing")
        assertThat(executionOrder).doesNotContain("should_not_run")
    }

    // ========== NESTED SCHEDULING TESTS ==========

    @Test
    fun `scheduled commands can schedule additional commands`() {
        // Given - nested scheduling
        val parentCommand =
            TestCommand {
                executionOrder.add("parent")
                commandExecutor.schedule {
                    TestCommand {
                        executionOrder.add("first")
                        commandExecutor.schedule {
                            TestCommand { executionOrder.add("nested") }
                        }
                    }
                }
            }

        // When
        val exitCode = commandExecutor.executeTopLevel(parentCommand)

        // Then
        assertThat(exitCode).isEqualTo(0)
        assertThat(executionOrder).containsExactly("parent", "first", "nested")
    }

    // ========== BACKUP TRIGGER TESTS ==========

    @Test
    fun `execute should trigger backup for command with TriggerBackup annotation`() {
        // Given
        val state = ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "test-bucket")
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockBackupRestoreService.backupChanged(any(), any()))
            .thenReturn(
                Result.success(
                    IncrementalBackupResult(
                        filesChecked = 0,
                        filesUploaded = 0,
                        filesSkipped = 0,
                        updatedHashes = emptyMap(),
                    ),
                ),
            )

        // When
        val exitCode =
            commandExecutor.execute {
                BackupTriggeringCommand { executionOrder.add("backup_command") }
            }

        // Then
        assertThat(exitCode).isEqualTo(0)
        verify(mockBackupRestoreService).backupChanged(any(), any())
    }

    @Test
    fun `execute should not trigger backup for command without TriggerBackup annotation`() {
        // When
        val exitCode =
            commandExecutor.execute {
                TestCommand { executionOrder.add("regular_command") }
            }

        // Then
        assertThat(exitCode).isEqualTo(0)
        verify(mockBackupRestoreService, never()).backupChanged(any(), any())
    }

    @Test
    fun `execute should not trigger backup when command fails`() {
        // When
        val exitCode =
            commandExecutor.execute {
                FailingBackupTriggeringCommand()
            }

        // Then
        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        verify(mockBackupRestoreService, never()).backupChanged(any(), any())
    }

    @Test
    fun `scheduled commands with TriggerBackup should trigger backup after execution`() {
        // Given
        val state = ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "test-bucket")
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockBackupRestoreService.backupChanged(any(), any()))
            .thenReturn(
                Result.success(
                    IncrementalBackupResult(
                        filesChecked = 1,
                        filesUploaded = 1,
                        filesSkipped = 0,
                        updatedHashes = mapOf("file1" to "hash1"),
                    ),
                ),
            )

        // When
        val parentCommand =
            TestCommand {
                executionOrder.add("parent")
                commandExecutor.schedule {
                    BackupTriggeringCommand { executionOrder.add("scheduled_backup") }
                }
            }
        val exitCode = commandExecutor.executeTopLevel(parentCommand)

        // Then
        assertThat(exitCode).isEqualTo(0)
        verify(mockBackupRestoreService).backupChanged(any(), any())
    }

    // ========== PROXY STARTUP TESTS ==========

    @Test
    fun `executeTopLevel does not start proxy for a command without RequiresProxy even when infra is UP`() {
        // Given - cluster is fully provisioned, infra UP, Tailscale disabled: every gating
        // condition ensureProxyRunning checks is satisfied. Only the missing annotation should
        // stop the proxy from starting.
        val controlHost =
            ClusterHost(
                publicIp = "1.2.3.4",
                privateIp = "10.0.0.1",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        val state =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                infrastructureStatus = InfrastructureStatus.UP,
                tailscaleActive = false,
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
            )
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)

        // When - TestCommand carries no @RequiresProxy
        commandExecutor.executeTopLevel(TestCommand { })

        // Then
        verify(mockSocksProxyService, never()).ensureRunning(any())
    }

    @Test
    fun `a command without RequiresProxy restores a lost direct key before it runs and starts no tunnel`() {
        recordEnvironment(Environment.K3sDocker("/data"))
        val envFile = ProxyEnvFile(context.workingDirectory)
        var directWhenRun: Boolean? = null

        val exitCode = commandExecutor.executeTopLevel(TestCommand { directWhenRun = envFile.read().direct })

        assertThat(exitCode).isZero()
        assertThat(directWhenRun).isTrue()
        verifyNoInteractions(mockSocksProxyService)
    }

    @Test
    fun `executeTopLevel starts proxy for a RequiresProxy command when infra is UP and Tailscale is disabled`() {
        // Given
        val controlHost =
            ClusterHost(
                publicIp = "1.2.3.4",
                privateIp = "10.0.0.1",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        val state =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                infrastructureStatus = InfrastructureStatus.UP,
                tailscaleActive = false,
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
            )
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)

        // When
        commandExecutor.executeTopLevel(ProxyRequiringCommand { })

        // Then
        verify(mockSocksProxyService).ensureRunning(controlHost)
    }

    @Test
    fun `executeTopLevel skips proxy for a RequiresProxy command when Tailscale is enabled`() {
        // Given
        val state =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                infrastructureStatus = InfrastructureStatus.UP,
                tailscaleActive = true,
            )
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)

        // When
        commandExecutor.executeTopLevel(ProxyRequiringCommand { })

        // Then
        verify(mockSocksProxyService, never()).ensureRunning(any())
        // The shell-side tool wrappers learn from the env file that this cluster needs no tunnel.
        assertThat(ProxyEnvFile(context.workingDirectory).read().tailscaleActive).isTrue()
    }

    @Test
    fun `executeTopLevel skips proxy for a RequiresProxy command when no state file exists`() {
        // Given
        whenever(mockClusterStateManager.exists()).thenReturn(false)

        // When
        commandExecutor.executeTopLevel(ProxyRequiringCommand { })

        // Then
        verify(mockSocksProxyService, never()).ensureRunning(any())
    }

    @Test
    fun `executeTopLevel propagates proxy establishment failure and returns non-zero for a RequiresProxy command`() {
        // Given
        val controlHost =
            ClusterHost(
                publicIp = "1.2.3.4",
                privateIp = "10.0.0.1",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        val state =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                infrastructureStatus = InfrastructureStatus.UP,
                tailscaleActive = false,
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
            )
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockSocksProxyService.ensureRunning(controlHost))
            .thenThrow(RuntimeException("port never began accepting connections — see socks5-proxy.log"))

        // When - the command body itself must never run once the proxy fails to establish
        val exitCode =
            commandExecutor.executeTopLevel(
                ProxyRequiringCommand { executionOrder.add("should_not_run") },
            )

        // Then - the failure propagates out of the proxy check and is reported like any other
        // command failure, not swallowed into a warning that lets the command proceed
        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(executionOrder).doesNotContain("should_not_run")
    }

    @Test
    fun `executeTopLevel runs a tolerateFailure command's body even when proxy establishment fails`() {
        // Given - RequiresProxy(tolerateFailure = true) is the narrow, explicit opt-in that lets
        // a command survive a proxy failure. Everything else about the failure mode is identical
        // to the non-tolerant case: ensureRunning still throws.
        val controlHost =
            ClusterHost(
                publicIp = "1.2.3.4",
                privateIp = "10.0.0.1",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        val state =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                infrastructureStatus = InfrastructureStatus.UP,
                tailscaleActive = false,
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
            )
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockSocksProxyService.ensureRunning(controlHost))
            .thenThrow(RuntimeException("port never began accepting connections — see socks5-proxy.log"))

        // When
        val exitCode =
            commandExecutor.executeTopLevel(
                ToleratingProxyRequiringCommand { executionOrder.add("ran despite proxy failure") },
            )

        // Then - unlike the non-tolerant case, the command body runs and the command succeeds
        assertThat(exitCode).isEqualTo(0)
        assertThat(executionOrder).containsExactly("ran despite proxy failure")
    }

    @Test
    fun `executeTopLevel records the proxy failure so a tolerateFailure command can observe it`() {
        // Given
        val controlHost =
            ClusterHost(
                publicIp = "1.2.3.4",
                privateIp = "10.0.0.1",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        val state =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                infrastructureStatus = InfrastructureStatus.UP,
                tailscaleActive = false,
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
            )
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockSocksProxyService.ensureRunning(controlHost))
            .thenThrow(RuntimeException("port never began accepting connections — see socks5-proxy.log"))
        var observedFailureMessage: String? = null

        // When - the command reads ProxyAvailability itself, from inside its own execute()
        commandExecutor.executeTopLevel(
            ToleratingProxyRequiringCommand {
                observedFailureMessage = proxyAvailability.failure()?.message
            },
        )

        // Then - the command actually observed why the proxy failed, not just that it failed
        assertThat(observedFailureMessage).contains("port never began accepting connections")
    }

    @Test
    fun `executeTopLevel clears a stale proxy failure before the next command runs`() {
        // Given - a tolerant command fails to establish the proxy and records it
        val controlHost =
            ClusterHost(
                publicIp = "1.2.3.4",
                privateIp = "10.0.0.1",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        val state =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                infrastructureStatus = InfrastructureStatus.UP,
                tailscaleActive = false,
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
            )
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockSocksProxyService.ensureRunning(controlHost))
            .thenThrow(RuntimeException("port never began accepting connections — see socks5-proxy.log"))
        commandExecutor.executeTopLevel(ToleratingProxyRequiringCommand { })
        assertThat(proxyAvailability.failure()).isNotNull()

        // When - a later command runs in the same (long-running Server/Repl) process
        commandExecutor.execute { TestCommand { } }

        // Then - the stale failure from the earlier command must not leak into this one
        assertThat(proxyAvailability.failure()).isNull()
    }

    @Test
    fun `Down carries no RequiresProxy so the executor never pre-flights a tunnel it would tear down`() {
        // Down must NOT carry @RequiresProxy. Its teardown path kills the tunnel, so an executor
        // pre-flight would only start a tunnel Down immediately destroys. Down does establish a
        // short-lived tunnel inside its own execute() to flush logs and metrics first
        // (design D3), so this invariant is asserted on the annotation directly rather than on
        // ensureRunning calls, which Down's own backup path now makes.
        //
        // The executor's "no pre-flight without @RequiresProxy" mechanism is covered separately by
        // `executeTopLevel does not start proxy for a command without RequiresProxy even when infra is UP`.
        assertThat(Down::class.java.getAnnotation(RequiresProxy::class.java)).isNull()
    }

    // ========== TEST COMMAND HELPERS ==========

    /** Simple test command that executes a provided action */
    @Command(name = "test-command")
    inner class TestCommand(
        private val action: () -> Unit = {},
    ) : PicoBaseCommand() {
        override fun execute() {
            action()
        }
    }

    /** Test command declared AWS-only the way `cassandra use` is, otherwise identical to [TestCommand]. */
    @RequiresCapability(NodeCapability.NODE_SSH, NodeCapability.AWS_ACCOUNT, alternative = "the cassandra-pods kit")
    @RequireProfileSetup
    @Command(name = "aws-only-command")
    inner class AwsOnlyCommand(
        private val action: () -> Unit = {},
    ) : PicoBaseCommand() {
        override fun execute() {
            action()
        }
    }

    /** Test command carrying [RequireProfileSetup], otherwise identical to [TestCommand]. */
    @RequireProfileSetup
    @Command(name = "profile-requiring-command")
    inner class ProfileRequiringCommand(
        private val action: () -> Unit = {},
    ) : PicoBaseCommand() {
        override fun execute() {
            action()
        }
    }

    /** A profile command that, like `init`, is about to record [environment]. */
    @RequireProfileSetup
    @Command(name = "declaring-command")
    inner class DeclaringCommand(
        private val environment: Environment,
        private val action: () -> Unit = {},
    ) : PicoBaseCommand(),
        DeclaresEnvironment {
        override fun declaredEnvironment(): Environment = environment

        override fun execute() {
            action()
        }
    }

    /** Test command carrying [RequiresProxy], otherwise identical to [TestCommand]. */
    @RequiresProxy
    @Command(name = "proxy-requiring-command")
    inner class ProxyRequiringCommand(
        private val action: () -> Unit = {},
    ) : PicoBaseCommand() {
        override fun execute() {
            action()
        }
    }

    /**
     * Test command carrying `@RequiresProxy(tolerateFailure = true)` — the narrow opt-in that
     * lets a command survive a proxy establishment failure instead of aborting. Otherwise
     * identical to [TestCommand].
     */
    @RequiresProxy(tolerateFailure = true)
    @Command(name = "tolerating-proxy-requiring-command")
    inner class ToleratingProxyRequiringCommand(
        private val action: () -> Unit = {},
    ) : PicoBaseCommand() {
        override fun execute() {
            action()
        }
    }

    /** Test command that fails with an exception */
    @Command(name = "failing-command")
    inner class FailingCommand : PicoBaseCommand() {
        override fun execute(): Unit = throw RuntimeException("Command failed intentionally")
    }

    /** Test command that fails with the given throwable */
    @Command(name = "throwing-command")
    inner class ThrowingCommand(
        private val failure: Throwable,
    ) : PicoBaseCommand() {
        override fun execute(): Unit = throw failure
    }

    /** Test command that loads cluster state from a workspace that has none */
    @Command(name = "missing-state-command")
    inner class MissingStateCommand(
        private val stateFile: File,
    ) : PicoBaseCommand() {
        override fun execute() {
            ClusterStateManager(stateFile).load()
        }
    }

    /** Test command that reports its own failure, then signals it through the exit code only */
    @Command(name = "reported-failure-command")
    inner class ReportedFailureCommand : PicoBaseCommand() {
        override fun execute(): Unit = throw CommandFailedException("query failed")
    }

    /** Test command with @TriggerBackup annotation */
    @TriggerBackup
    @Command(name = "backup-triggering-command")
    inner class BackupTriggeringCommand(
        private val action: () -> Unit = {},
    ) : PicoBaseCommand() {
        override fun execute() {
            action()
        }
    }

    /** Test command with @TriggerBackup that fails */
    @TriggerBackup
    @Command(name = "failing-backup-command")
    inner class FailingBackupTriggeringCommand : PicoBaseCommand() {
        override fun execute(): Unit = throw RuntimeException("Backup command failed")
    }
}
