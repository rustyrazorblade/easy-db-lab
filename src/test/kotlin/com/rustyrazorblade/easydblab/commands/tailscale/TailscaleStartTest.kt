package com.rustyrazorblade.easydblab.commands.tailscale

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.TailscaleApiException
import com.rustyrazorblade.easydblab.services.TailscaleAuthKey
import com.rustyrazorblade.easydblab.services.TailscaleService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TailscaleStartTest : BaseKoinTest() {
    private lateinit var mockTailscaleService: TailscaleService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    private val testControlHost =
        ClusterHost(
            publicIp = "54.1.2.5",
            privateIp = "10.0.1.3",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control0",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2", cidr = "10.0.0.0/16"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(testControlHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<TailscaleService> { mockTailscaleService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockTailscaleService = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockTailscaleService.isConnected(any())).thenReturn(Result.success(false))
        whenever(mockTailscaleService.getDeviceId(any())).thenReturn(Result.success("nDefaultCNTRL"))
    }

    @Nested
    inner class MissingCredentials {
        @Test
        fun `execute shows error when no credentials configured`() {
            // Default test user has blank tailscale credentials
            val command = TailscaleStart()
            command.execute()

            val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
            assertThat(errorOutput).contains("OAuth credentials not configured")
        }
    }

    @Nested
    inner class NoControlNode {
        @Test
        fun `execute shows error when no control node exists`() {
            val stateNoControl =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    initConfig = InitConfig(region = "us-west-2"),
                    hosts = emptyMap(),
                )
            whenever(mockClusterStateManager.load()).thenReturn(stateNoControl)

            val command = TailscaleStart()
            command.clientId = "client-id"
            command.clientSecret = "client-secret"
            command.execute()

            val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
            assertThat(errorOutput).contains("No control node found")
        }
    }

    @Nested
    inner class AlreadyConnected {
        @Test
        fun `execute shows already connected message`() {
            whenever(mockTailscaleService.isConnected(any())).thenReturn(Result.success(true))
            whenever(mockTailscaleService.getStatus(any())).thenReturn(Result.success("Connected"))

            val command = TailscaleStart()
            command.clientId = "client-id"
            command.clientSecret = "client-secret"
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("already connected")
        }
    }

    @Nested
    inner class SuccessfulStart {
        @Test
        fun `execute starts tailscale with CLI credentials`() {
            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenReturn(TailscaleAuthKey(key = "auth-key-123", id = "key-id-123"))
            whenever(mockTailscaleService.startTailscale(any(), any(), any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockTailscaleService.getStatus(any()))
                .thenReturn(Result.success("Connected - IP: 100.64.0.1"))

            val command = TailscaleStart()
            command.clientId = "my-client-id"
            command.clientSecret = "my-client-secret"
            command.execute()

            verify(mockTailscaleService).generateAuthKey(
                eq("my-client-id"),
                eq("my-client-secret"),
                any(),
            )
            verify(mockTailscaleService).startTailscale(any(), eq("auth-key-123"), any(), any())

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("started successfully")
        }

        @Test
        fun `execute advertises VPC CIDR as subnet route`() {
            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenReturn(TailscaleAuthKey(key = "auth-key-123", id = "key-id-123"))
            whenever(mockTailscaleService.startTailscale(any(), any(), any(), eq("10.0.0.0/16")))
                .thenReturn(Result.success(Unit))
            whenever(mockTailscaleService.getStatus(any()))
                .thenReturn(Result.success("Connected"))

            val command = TailscaleStart()
            command.clientId = "client-id"
            command.clientSecret = "client-secret"
            command.execute()

            verify(mockTailscaleService).startTailscale(any(), any(), any(), eq("10.0.0.0/16"))
        }
    }

    @Nested
    inner class KeyIdPersistence {
        @Test
        fun `execute persists auth key ID to cluster state`() {
            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenReturn(TailscaleAuthKey(key = "auth-key-123", id = "key-id-456"))
            whenever(mockTailscaleService.startTailscale(any(), any(), any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockTailscaleService.getStatus(any()))
                .thenReturn(Result.success("Connected"))

            val command = TailscaleStart()
            command.clientId = "client-id"
            command.clientSecret = "client-secret"
            command.execute()

            assertThat(testClusterState.tailscaleAuthKeyId).isEqualTo("key-id-456")
            verify(mockClusterStateManager, atLeastOnce()).save(testClusterState)
        }

        @Test
        fun `execute records the control node's tailnet device ID so down can delete it`() {
            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenReturn(TailscaleAuthKey(key = "auth-key-123", id = "key-id-456"))
            whenever(mockTailscaleService.startTailscale(any(), any(), any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockTailscaleService.getDeviceId(any())).thenReturn(Result.success("nControl0CNTRL"))
            whenever(mockTailscaleService.getStatus(any()))
                .thenReturn(Result.success("Connected"))

            val command = TailscaleStart()
            command.clientId = "client-id"
            command.clientSecret = "client-secret"
            command.execute()

            assertThat(testClusterState.tailscaleDeviceId).isEqualTo("nControl0CNTRL")
        }
    }

    /** A device recorded by an earlier start is replaced; left in place it would outlive the cluster. */
    @Nested
    inner class PreviouslyRecordedDevice {
        private fun stubSuccessfulStart(newDeviceId: String) {
            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenReturn(TailscaleAuthKey(key = "auth-key-123", id = "key-id-456"))
            whenever(mockTailscaleService.startTailscale(any(), any(), any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockTailscaleService.getDeviceId(any())).thenReturn(Result.success(newDeviceId))
            whenever(mockTailscaleService.getStatus(any())).thenReturn(Result.success("Connected"))
        }

        private fun startCommand(): TailscaleStart =
            TailscaleStart().apply {
                clientId = "client-id"
                clientSecret = "client-secret"
            }

        @Test
        fun `a different previously recorded device is removed before the new one is recorded`() {
            testClusterState.tailscaleDeviceId = "nOldCNTRL"
            stubSuccessfulStart("nNewCNTRL")

            val exitCode = startCommand().call()

            assertThat(exitCode).isEqualTo(0)
            verify(mockTailscaleService).deleteDevice(eq("client-id"), eq("client-secret"), eq("nOldCNTRL"))
            assertThat(testClusterState.tailscaleDeviceId).isEqualTo("nNewCNTRL")
            assertThat(outputHandler.messages.joinToString("\n")).contains("nOldCNTRL")
        }

        @Test
        fun `the same device re-registering is not deleted`() {
            testClusterState.tailscaleDeviceId = "nSameCNTRL"
            stubSuccessfulStart("nSameCNTRL")

            assertThat(startCommand().call()).isEqualTo(0)

            verify(mockTailscaleService, never()).deleteDevice(any(), any(), any())
            assertThat(testClusterState.tailscaleDeviceId).isEqualTo("nSameCNTRL")
        }

        /**
         * Tailscale came up, so the command succeeds: `up` treats a non-zero exit as Tailscale not
         * starting. The new device is the live one, so it is recorded for `down`; the old one is
         * named in the error so it can be removed by hand.
         */
        @Test
        fun `a failed removal of the old device is reported but still succeeds, recording the live device`() {
            testClusterState.tailscaleDeviceId = "nOldCNTRL"
            stubSuccessfulStart("nNewCNTRL")
            whenever(mockTailscaleService.deleteDevice(any(), any(), any()))
                .thenThrow(TailscaleApiException("not allowed to delete device nOldCNTRL; grant it the 'devices:core' write scope"))

            val exitCode = startCommand().call()

            assertThat(exitCode).isEqualTo(0)
            assertThat(testClusterState.tailscaleDeviceId).isEqualTo("nNewCNTRL")
            verify(mockClusterStateManager, atLeastOnce()).save(testClusterState)
            val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
            assertThat(errorOutput).contains("nOldCNTRL").contains("devices:core")
        }
    }

    @Nested
    inner class FailedStart {
        @Test
        fun `execute shows error on TailscaleApiException`() {
            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenThrow(TailscaleApiException("Invalid tags"))

            val command = TailscaleStart()
            command.clientId = "client-id"
            command.clientSecret = "client-secret"
            command.execute()

            val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
            assertThat(errorOutput).contains("Failed to start Tailscale")
            assertThat(errorOutput).contains("Invalid tags")
        }

        /** `up` checks this exit code to decide whether Tailscale came up. */
        @Test
        fun `a Tailscale API failure makes the command exit non-zero`() {
            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenThrow(TailscaleApiException("401 unauthorized"))

            val command = TailscaleStart()
            command.clientId = "client-id"
            command.clientSecret = "client-secret"

            assertThat(command.call()).isEqualTo(Constants.ExitCodes.ERROR)
        }

        @Test
        fun `missing credentials make the command exit non-zero`() {
            assertThat(TailscaleStart().call()).isEqualTo(Constants.ExitCodes.ERROR)
        }

        /** The scopes named here are the ones start needs and `down`/`stop` report when missing. */
        @Test
        fun `the missing-credentials help names the OAuth scopes by their current names`() {
            TailscaleStart().call()

            val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
            assertThat(errorOutput).contains("auth_keys").contains("devices:core").doesNotContain("Devices: write")
        }

        @Test
        fun `execute shows ACL hint when error mentions tags`() {
            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenThrow(TailscaleApiException("Invalid tags configuration"))

            val command = TailscaleStart()
            command.clientId = "client-id"
            command.clientSecret = "client-secret"
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("must be configured in your Tailscale ACL")
        }
    }

    @Nested
    inner class UserConfigCredentials {
        @Test
        fun `execute uses user config credentials when CLI not provided`() {
            val userWithTailscale =
                User(
                    email = "test@example.com",
                    region = "us-west-2",
                    keyName = "test-key",
                    awsProfile = "",
                    awsAccessKey = "test-access-key",
                    awsSecret = "test-secret",
                    axonOpsOrg = "",
                    axonOpsKey = "",
                    tailscaleClientId = "config-client-id",
                    tailscaleClientSecret = "config-client-secret",
                )
            getKoin().declare(userWithTailscale)

            whenever(mockTailscaleService.generateAuthKey(any(), any(), any()))
                .thenReturn(TailscaleAuthKey(key = "auth-key", id = "key-id"))
            whenever(mockTailscaleService.startTailscale(any(), any(), any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockTailscaleService.getStatus(any()))
                .thenReturn(Result.success("Connected"))

            val command = TailscaleStart()
            command.execute()

            verify(mockTailscaleService).generateAuthKey(
                eq("config-client-id"),
                eq("config-client-secret"),
                any(),
            )
        }
    }
}
