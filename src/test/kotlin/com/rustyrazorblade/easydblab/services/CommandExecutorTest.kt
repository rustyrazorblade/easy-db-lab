package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.Down
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.proxy.DefaultProxyAvailability
import com.rustyrazorblade.easydblab.proxy.ProxyAvailability
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import picocli.CommandLine.Command

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
            },
        )

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

        commandExecutor =
            DefaultCommandExecutor(
                context = context,
                clusterStateManager = mockClusterStateManager,
                requirementCheckDeps =
                    RequirementCheckDeps(
                        userConfigProvider = mockUserConfigProvider,
                        dockerClientProvider = mockDockerClientProvider,
                    ),
                resourceManager = mockResourceManager,
                eventBus = EventBus(),
                socksProxyService = mockSocksProxyService,
                proxyAvailability = proxyAvailability,
            )
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
    fun `executeTopLevel does not start proxy for Down even when infra is UP and Tailscale is disabled`() {
        // Given - Down is never annotated with @RequiresProxy: it tears down the tunnel itself
        // as its first action (clearProxySystemProperties + cleanupSocks5Proxy), so a pre-flight
        // proxy start would only start a tunnel Down immediately destroys.
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

        // When - Down's own execute() may fail against this test's minimal AWS mocks; that is
        // irrelevant here, only whether the proxy pre-flight ran is under test.
        commandExecutor.execute { Down() }

        // Then
        verify(mockSocksProxyService, never()).ensureRunning(any())
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
