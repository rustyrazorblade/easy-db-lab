package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
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
    private lateinit var mockOutputHandler: OutputHandler
    private lateinit var mockUserConfigProvider: UserConfigProvider
    private lateinit var mockDockerClientProvider: DockerClientProvider
    private lateinit var mockResourceManager: ResourceManager
    private lateinit var mockUserConfig: User
    private lateinit var commandExecutor: DefaultCommandExecutor

    // Track command execution order
    private val executionOrder = mutableListOf<String>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<BackupRestoreService> { mockBackupRestoreService }
                single<OutputHandler> { mockOutputHandler }
                single<UserConfigProvider> { mockUserConfigProvider }
                single<DockerClientProvider> { mockDockerClientProvider }
                single<ResourceManager> { mockResourceManager }
                single<User> { mockUserConfig }
            },
        )

    @BeforeEach
    fun setupMocks() {
        executionOrder.clear()
        mockClusterStateManager = mock()
        mockBackupRestoreService = mock()
        mockOutputHandler = mock()
        mockUserConfigProvider = mock()
        mockDockerClientProvider = mock()
        mockResourceManager = mock()
        mockUserConfig = mock()

        // Default: profile is already set up
        whenever(mockUserConfigProvider.isSetup()).thenReturn(true)

        commandExecutor =
            DefaultCommandExecutor(
                context = context,
                clusterStateManager = mockClusterStateManager,
                outputHandler = mockOutputHandler,
                userConfigProvider = mockUserConfigProvider,
                dockerClientProvider = mockDockerClientProvider,
                resourceManager = mockResourceManager,
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
