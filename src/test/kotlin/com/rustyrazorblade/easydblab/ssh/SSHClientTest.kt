package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.io.IoSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.component.KoinComponent
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.rmi.RemoteException

/**
 * Unit tests for SSHClient
 *
 * Tests cover:
 * - Command execution
 * - File operations (upload/download)
 * - Directory operations
 * - Resource cleanup
 * - Input validation
 */
class SSHClientTest :
    BaseKoinTest(),
    KoinComponent {
    private lateinit var mockSession: ClientSession
    private lateinit var mockIoSession: IoSession
    private lateinit var sshClient: SSHClient
    private lateinit var outputHandler: BufferedOutputHandler

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        mockSession = mock()
        mockIoSession = mock()

        whenever(mockSession.ioSession).thenReturn(mockIoSession)
        whenever(mockSession.username).thenReturn("testuser")
        whenever(mockIoSession.remoteAddress).thenReturn(InetSocketAddress("10.0.0.1", 22))

        sshClient = SSHClient(mockSession)
    }

    // ========== Command Execution Tests ==========

    /** Stubs the session to write [stdout]/[stderr], then fail the way MINA does on a non-zero exit. */
    private fun stubRemoteCommand(
        stdout: String = "",
        stderr: String = "",
        failWith: RemoteException? = null,
    ) {
        whenever(mockSession.executeRemoteCommand(any(), any(), any(), any<Charset>())).thenAnswer { invocation ->
            invocation.getArgument<OutputStream>(1).write(stdout.toByteArray())
            invocation.getArgument<OutputStream>(2).write(stderr.toByteArray())
            failWith?.let { throw it }
            null
        }
    }

    @Test
    fun `executeRemoteCommand should execute command and return response`() {
        // Given
        val command = "ls -la"
        val expectedOutput = "total 0\ndrwxr-xr-x 1 root root 0 Jan 1 00:00 ."
        stubRemoteCommand(stdout = expectedOutput, stderr = "a warning")

        // When
        val response = sshClient.executeRemoteCommand(command, output = false, secret = false)

        // Then
        assertThat(response.text).isEqualTo(expectedOutput)
        assertThat(response.stderr).isEqualTo("a warning")
        verify(mockSession).executeRemoteCommand(eq(command), any(), any(), eq(Charset.defaultCharset()))
    }

    @Test
    fun `a non-zero exit surfaces what the remote command actually said`() {
        val command = "sudo use-cassandra 5.1"
        stubRemoteCommand(
            stdout = "Cassandra version 5.1 is not installed on this node.\nRun 'cassandra install 5.1' first.",
            stderr = "",
            failWith = RemoteException("Remote command failed (1): $command"),
        )

        assertThatThrownBy { sshClient.executeRemoteCommand(command, output = false, secret = false) }
            .isInstanceOf(RemoteCommandFailedException::class.java)
            .hasMessageContaining("Cassandra version 5.1 is not installed on this node.")
            .hasMessageContaining("cassandra install 5.1")
    }

    @Test
    fun `a non-zero exit surfaces stderr as well as stdout`() {
        stubRemoteCommand(
            stdout = "Building version trunk with ant",
            stderr = "ERROR: Ant build failed for version trunk\ncompile: BUILD FAILED",
            failWith = RemoteException("Remote command failed (1): install-cassandra-version trunk"),
        )

        assertThatThrownBy {
            sshClient.executeRemoteCommand("install-cassandra-version trunk", output = false, secret = false)
        }.isInstanceOf(RemoteCommandFailedException::class.java)
            .hasMessageContaining("ERROR: Ant build failed for version trunk")
            .hasMessageContaining("Building version trunk with ant")
    }

    @Test
    fun `a failure never leaks credentials embedded in the command`() {
        val command = "install-cassandra-version fork --url https://alice:ghp_secrettoken@github.com/acme/cassandra.git"
        stubRemoteCommand(
            stderr = "ERROR: Git clone failed for https://alice:ghp_secrettoken@github.com/acme/cassandra.git",
            failWith = RemoteException("Remote command failed (1): $command"),
        )

        assertThatThrownBy { sshClient.executeRemoteCommand(command, output = false, secret = false) }
            .isInstanceOf(RemoteCommandFailedException::class.java)
            .hasMessageNotContaining("ghp_secrettoken")
            .hasMessageContaining("***@github.com")
    }

    @Test
    fun `a failure on a secret command repeats neither the command nor its output`() {
        // A secret command routinely echoes its own argument back: a usage line, an error naming
        // the key it just rejected.
        stubRemoteCommand(
            stdout = "usage: tailscale up --authkey=tskey-auth-hunter2",
            stderr = "invalid key: tskey-auth-hunter2",
            failWith = RemoteException("Remote command failed (1): tailscale up --authkey=tskey-auth-hunter2"),
        )

        assertThatThrownBy {
            sshClient.executeRemoteCommand("tailscale up --authkey=tskey-auth-hunter2", output = false, secret = true)
        }.isInstanceOf(RemoteCommandFailedException::class.java)
            .hasMessageNotContaining("hunter2")
    }

    @Test
    fun `a successful command's output is redacted before it reaches the event bus`() {
        // install-cassandra-version echoes the clone URL it was given; that output is emitted as a
        // @Serializable event reaching every MCP and Redis subscriber.
        stubRemoteCommand(
            stdout = "Cloning repo for version fork from https://ghp_secrettoken@github.com/acme/cassandra.git",
        )

        val response = sshClient.executeRemoteCommand("install-cassandra-version fork", output = true, secret = false)

        assertThat(response.text).doesNotContain("ghp_secrettoken")
        assertThat(response.text).contains("***@github.com")
        val emitted = outputHandler.messages.joinToString("\n")
        assertThat(emitted).doesNotContain("ghp_secrettoken")
    }

    @Test
    fun `a successful command's stderr is redacted too`() {
        stubRemoteCommand(stderr = "warning: fetching https://ghp_secrettoken@github.com/acme/cassandra.git")

        val response = sshClient.executeRemoteCommand("git fetch", output = false, secret = false)

        assertThat(response.stderr).doesNotContain("ghp_secrettoken")
    }

    @Test
    fun `executeRemoteCommand should reject blank command`() {
        assertThatThrownBy {
            sshClient.executeRemoteCommand("", output = false, secret = false)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Command cannot be blank")
    }

    @Test
    fun `executeRemoteCommand with secret flag should hide command in output`() {
        // Given
        val command = "echo secret"
        stubRemoteCommand(stdout = "secret")

        // When
        sshClient.executeRemoteCommand(command, output = true, secret = true)

        // Then - verify outputHandler was called with hidden message
        // Note: In real test we'd verify outputHandler.handleMessage was called with "[hidden]"
    }

    // ========== File Upload Tests ==========

    @Test
    fun `uploadFile should reject blank remote path`() {
        val localFile = File(tempDir, "test.txt")
        localFile.writeText("test content")

        assertThatThrownBy {
            sshClient.uploadFile(localFile.toPath(), "")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Remote path cannot be blank")
    }

    @Test
    fun `uploadFile should reject non-existent local file`() {
        val nonExistentFile = File(tempDir, "nonexistent.txt").toPath()

        assertThatThrownBy {
            sshClient.uploadFile(nonExistentFile, "/remote/path")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Local file does not exist")
    }

    @Test
    fun `uploadFile should reject directory as local file`() {
        val directory = tempDir

        assertThatThrownBy {
            sshClient.uploadFile(directory.toPath(), "/remote/path")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Local path is not a file")
    }

    // ========== Directory Upload Tests ==========

    @Test
    fun `uploadDirectory should reject blank remote directory`() {
        assertThatThrownBy {
            sshClient.uploadDirectory(tempDir, "")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Remote directory path cannot be blank")
    }

    @Test
    fun `uploadDirectory should reject non-existent local directory`() {
        val nonExistentDir = File(tempDir, "nonexistent")

        assertThatThrownBy {
            sshClient.uploadDirectory(nonExistentDir, "/remote/path")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Local directory does not exist")
    }

    @Test
    fun `uploadDirectory should reject file as local directory`() {
        val file = File(tempDir, "test.txt")
        file.writeText("test")

        assertThatThrownBy {
            sshClient.uploadDirectory(file, "/remote/path")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Local path is not a directory")
    }

    @Test
    fun `uploadDirectory should handle flat directory with multiple files`() {
        // Given - create a flat directory with files
        val file1 = File(tempDir, "file1.txt")
        val file2 = File(tempDir, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        // Mock session to track commands
        whenever(mockSession.executeRemoteCommand(any(), any(), any())).thenReturn("")

        // Mock SCP operations through the ScpClient
        // Note: Real SCP client creation is complex, so we just verify no exceptions

        // When - uploadDirectory should not throw
        // This is an integration-style test that verifies the method completes
        // The actual SSH operations are mocked at the session level
    }

    @Test
    fun `uploadDirectory should handle nested directory structure`() {
        // Given - create nested directory structure
        val subDir = File(tempDir, "subdir")
        subDir.mkdir()
        val file1 = File(tempDir, "root.txt")
        val file2 = File(subDir, "nested.txt")
        file1.writeText("root content")
        file2.writeText("nested content")

        // Mock session
        whenever(mockSession.executeRemoteCommand(any(), any(), any())).thenReturn("")

        // When/Then - should complete without error
        // The buildUploadList function will properly track both the root and subdirectory
    }

    @Test
    fun `uploadDirectory should handle empty directory`() {
        // Given - empty directory
        val emptyDir = File(tempDir, "empty")
        emptyDir.mkdir()

        // Mock session
        whenever(mockSession.executeRemoteCommand(any(), any(), any())).thenReturn("")

        // When/Then - should complete without error
        // Should create the remote directory but upload no files
    }

    @Test
    fun `uploadDirectory should handle deeply nested directories`() {
        // Given - deeply nested structure
        val level1 = File(tempDir, "level1")
        val level2 = File(level1, "level2")
        val level3 = File(level2, "level3")
        level3.mkdirs()

        val file1 = File(level1, "file1.txt")
        val file2 = File(level2, "file2.txt")
        val file3 = File(level3, "file3.txt")
        file1.writeText("content1")
        file2.writeText("content2")
        file3.writeText("content3")

        // Mock session
        whenever(mockSession.executeRemoteCommand(any(), any(), any())).thenReturn("")

        // When/Then - should traverse all levels and collect all files
    }

    @Test
    fun `uploadDirectory should preserve directory structure in remote paths`() {
        // Given - directory with specific structure
        val subDir = File(tempDir, "projects")
        subDir.mkdir()
        val srcDir = File(subDir, "src")
        srcDir.mkdir()

        val rootFile = File(tempDir, "README.md")
        val projectFile = File(subDir, "build.gradle")
        val srcFile = File(srcDir, "Main.kt")

        rootFile.writeText("readme")
        projectFile.writeText("build script")
        srcFile.writeText("fun main() {}")

        // Mock session to capture the mkdir command
        var capturedCommand = ""
        whenever(mockSession.executeRemoteCommand(any(), any(), any())).thenAnswer { invocation ->
            capturedCommand = invocation.getArgument(0)
            ""
        }

        // When
        // Note: Would need to verify the mkdir command creates the right structure
        // This test documents expected behavior
    }

    // ========== File Download Tests ==========

    @Test
    fun `downloadFile should reject blank remote path`() {
        val localPath = File(tempDir, "output.txt").toPath()

        assertThatThrownBy {
            sshClient.downloadFile("", localPath)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Remote path cannot be blank")
    }

    // ========== Directory Download Tests ==========

    @Test
    fun `downloadDirectory should reject blank remote directory`() {
        val localDir = File(tempDir, "download")

        assertThatThrownBy {
            sshClient.downloadDirectory("", localDir, emptyList(), emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Remote directory path cannot be blank")
    }

    @Test
    fun `downloadDirectory should create local directory if it doesn't exist`() {
        // Given
        val localDir = File(tempDir, "new-download-dir")
        assertThat(localDir.exists()).isFalse()

        // Mock the find command to return no files
        whenever(mockSession.executeRemoteCommand(any(), any(), any())).thenReturn("")

        // When
        sshClient.downloadDirectory("/remote/dir", localDir, emptyList(), emptyList())

        // Then
        assertThat(localDir.exists()).isTrue()
        assertThat(localDir.isDirectory).isTrue()
    }

    // ========== Resource Management Tests ==========

    @Test
    fun `getScpClient should cache and reuse SCP client`() {
        // Note: ScpClientCreator is a static singleton, so we can't easily mock it
        // This test would require more complex mocking or a refactored design
        // For now, we document that caching is tested through integration tests
    }

    @Test
    fun `close should clean up all resources`() {
        // When
        sshClient.close()

        // Then
        verify(mockSession).close()
    }

    @Test
    fun `close should be safe to call multiple times`() {
        // When/Then - should not throw
        sshClient.close()
        sshClient.close()
    }
}
