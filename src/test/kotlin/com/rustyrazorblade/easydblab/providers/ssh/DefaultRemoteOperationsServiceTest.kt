package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DefaultRemoteOperationsServiceTest :
    BaseKoinTest(),
    KoinComponent {
    private lateinit var service: DefaultRemoteOperationsService
    private lateinit var mockSSHConnectionProvider: SSHConnectionProvider
    private lateinit var mockSSHClient: ISSHClient
    private lateinit var host: Host

    companion object {
        const val CASSANDRA_BASE_PATH = "/usr/local/cassandra/"
        const val TEST_VERSION = "5.0"
        const val TEST_VERSION_PATH = "/usr/local/cassandra/5.0"
        const val TEST_HOST_IP = "10.0.0.1"
        const val TEST_HOST_NAME = "test-host"
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            org.koin.dsl.module {
                single { mockSSHConnectionProvider }
            },
        )

    @BeforeEach
    fun setup() {
        mockSSHConnectionProvider = mock()
        mockSSHClient = mock()
        host = Host(TEST_HOST_NAME, TEST_HOST_IP, "", "seed")

        // Setup the mock to return our mock SSH client
        whenever(mockSSHConnectionProvider.getConnection(any())).thenReturn(mockSSHClient)

        // Get the service from DI (it will use our mocked SSHConnectionProvider)
        val sshConnectionProvider: SSHConnectionProvider by inject()
        service = DefaultRemoteOperationsService(sshConnectionProvider)
    }

    @Test
    fun `test getRemoteVersion with input version`() {
        // When getting the version with a specific version
        val result = service.getRemoteVersion(host, TEST_VERSION)

        // Then verify the version component is correct and path is formed properly
        assertThat(result).isNotNull()
        assertThat(result.versionString).isEqualTo(TEST_VERSION)
        assertThat(result.path).isEqualTo(TEST_VERSION_PATH)
    }

    @Test
    fun `test getRemoteVersion from current symlink`() {
        // Given the remote command will return a path to version 5.0
        whenever(
            mockSSHClient.executeRemoteCommand(eq("readlink -f /usr/local/cassandra/current"), any(), any()),
        ).thenReturn(Response(TEST_VERSION_PATH))

        // When getting the current version
        val result = service.getRemoteVersion(host, "current")

        // Then verify the version is extracted correctly
        assertThat(result).isNotNull()
        assertThat(result.versionString).isEqualTo(TEST_VERSION)
        assertThat(result.path).isEqualTo(TEST_VERSION_PATH)
    }

    @Test
    fun `test getRemoteVersion with version containing dots`() {
        // Given a version with multiple dots
        val versionWithDots = "4.1.2"
        val expectedPath = "${CASSANDRA_BASE_PATH}$versionWithDots"

        // When getting the version
        val result = service.getRemoteVersion(host, versionWithDots)

        // Then verify the version is handled correctly
        assertThat(result).isNotNull()
        assertThat(result.versionString).isEqualTo(versionWithDots)
        assertThat(result.path).isEqualTo(expectedPath)
    }

    @Test
    fun `test getRemoteVersion with snapshot version`() {
        // Given a snapshot version
        val snapshotVersion = "5.0-SNAPSHOT"
        val expectedPath = "${CASSANDRA_BASE_PATH}$snapshotVersion"

        // When getting the version
        val result = service.getRemoteVersion(host, snapshotVersion)

        // Then verify snapshot versions are handled correctly
        assertThat(result).isNotNull()
        assertThat(result.versionString).isEqualTo(snapshotVersion)
        assertThat(result.path).isEqualTo(expectedPath)
    }

    @Test
    fun `test getRemoteVersion with alpha version`() {
        // Given an alpha version
        val alphaVersion = "5.1-alpha1"
        val expectedPath = "${CASSANDRA_BASE_PATH}$alphaVersion"

        // When getting the version
        val result = service.getRemoteVersion(host, alphaVersion)

        // Then verify alpha versions are handled correctly
        assertThat(result).isNotNull()
        assertThat(result.versionString).isEqualTo(alphaVersion)
        assertThat(result.path).isEqualTo(expectedPath)
    }

    @Test
    fun `test getRemoteVersion with beta version`() {
        // Given a beta version
        val betaVersion = "5.1-beta2"
        val expectedPath = "${CASSANDRA_BASE_PATH}$betaVersion"

        // When getting the version
        val result = service.getRemoteVersion(host, betaVersion)

        // Then verify beta versions are handled correctly
        assertThat(result).isNotNull()
        assertThat(result.versionString).isEqualTo(betaVersion)
        assertThat(result.path).isEqualTo(expectedPath)
    }

    @Test
    fun `test getRemoteVersion with release candidate version`() {
        // Given a release candidate version
        val rcVersion = "5.1-rc1"
        val expectedPath = "${CASSANDRA_BASE_PATH}$rcVersion"

        // When getting the version
        val result = service.getRemoteVersion(host, rcVersion)

        // Then verify RC versions are handled correctly
        assertThat(result).isNotNull()
        assertThat(result.versionString).isEqualTo(rcVersion)
        assertThat(result.path).isEqualTo(expectedPath)
    }

    @Test
    fun `test executeRemotely delegates to SSH client`() {
        // Given a command to execute
        val command = "ls -la"
        val expectedOutput = "file1\nfile2\n"
        whenever(mockSSHClient.executeRemoteCommand(eq(command), any(), any())).thenReturn(Response(expectedOutput))

        // When executing the command
        val result = service.executeRemotely(host, command)

        // Then verify the result
        assertThat(result).isNotNull()
        assertThat(result.text).isEqualTo(expectedOutput)
    }
}
