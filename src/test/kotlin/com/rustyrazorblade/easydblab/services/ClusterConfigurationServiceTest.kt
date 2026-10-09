package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.ssh.DirectSshRoute
import com.rustyrazorblade.easydblab.providers.ssm.SsmCliCredentials
import com.rustyrazorblade.easydblab.providers.ssm.SsmSessionCommandBuilder
import com.rustyrazorblade.easydblab.providers.ssm.SsmSshRoute
import com.rustyrazorblade.easydblab.proxy.ProxyEnv
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path

/**
 * Tests for ClusterConfigurationService.
 */
class ClusterConfigurationServiceTest {
    private lateinit var userConfigProvider: UserConfigProvider
    private lateinit var service: ClusterConfigurationService
    private lateinit var eventBus: EventBus
    private val capturedEvents = mutableListOf<EventEnvelope>()

    @TempDir
    lateinit var tempDir: Path

    companion object {
        const val TEST_SSH_KEY_PATH = "/path/to/key"
    }

    @BeforeEach
    fun setup() {
        userConfigProvider = mock()
        capturedEvents.clear()
        eventBus = EventBus()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope)
                }

                override fun close() = Unit
            },
        )
        whenever(userConfigProvider.sshKeyPath).thenReturn(TEST_SSH_KEY_PATH)
        service =
            DefaultClusterConfigurationService(
                userConfigProvider,
                eventBus,
                DirectSshRoute(22),
            )
    }

    private val ssmRoute =
        SsmSshRoute(
            SsmSessionCommandBuilder(
                "us-west-2",
                { SsmCliCredentials.NamedProfile("lab-profile") },
                sshProxyWrapper = { "/profiles/lab/edl-ssm-proxy" },
            ),
            22,
            { },
        )

    /** A service whose hosts are routed over SSM, as under a profile with the `ssm` transport. */
    private val ssmService by lazy { DefaultClusterConfigurationService(userConfigProvider, eventBus, ssmRoute) }

    @AfterEach
    fun closeRoute() = ssmRoute.close()

    @Nested
    inner class WriteAllConfigurationFiles {
        @Test
        fun `should write all configuration files successfully`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            val result = service.writeAllConfigurationFiles(tempDir, clusterState, userConfig)

            assertThat(result.isSuccess).isTrue()
            assertThat(File(tempDir.toFile(), "sshConfig")).exists()
            assertThat(File(tempDir.toFile(), "env.sh")).exists()
            assertThat(File(tempDir.toFile(), "environment.sh")).exists()
            assertThat(File(tempDir.toFile(), "axonops-workbench.json")).exists()
        }

        @Test
        fun `should handle empty Cassandra hosts gracefully`() {
            val clusterState = createClusterState(cassandraHosts = emptyList())
            val userConfig = createUserConfig()

            val result = service.writeAllConfigurationFiles(tempDir, clusterState, userConfig)

            assertThat(result.isSuccess).isTrue()
            // SSH and env files should still be created
            assertThat(File(tempDir.toFile(), "sshConfig")).exists()
            assertThat(File(tempDir.toFile(), "env.sh")).exists()
        }
    }

    @Nested
    inner class WriteSshAndEnvironmentFiles {
        @Test
        fun `on a Tailscale cluster it writes the wrappers and an env file that says Tailscale and has no port`() {
            val clusterState = createClusterState().copy(tailscaleActive = true)

            service.writeSshAndEnvironmentFiles(tempDir, clusterState, createUserConfig())

            assertThat(ProxyEnvFile(tempDir.toFile()).read()).isEqualTo(ProxyEnv(tailscaleActive = true, socksPort = null))
            val bin = File(tempDir.toFile(), Constants.ToolWrappers.DIRECTORY)
            assertThat(bin.list()).containsExactlyInAnyOrderElementsOf(Constants.ToolWrappers.TOOLS + Constants.ToolWrappers.MARKER)
        }

        @Test
        fun `on a SOCKS cluster it records that the cluster is not Tailscale and keeps a recorded port`() {
            ProxyEnvFile(tempDir.toFile()).recordPort(41234)

            service.writeSshAndEnvironmentFiles(tempDir, createClusterState().copy(tailscaleActive = false), createUserConfig())

            assertThat(ProxyEnvFile(tempDir.toFile()).read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = 41234))
            assertThat(File(tempDir.toFile(), "${Constants.ToolWrappers.DIRECTORY}/kubectl").canExecute()).isTrue()
        }

        @Test
        fun `should create sshConfig file`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            service.writeSshAndEnvironmentFiles(tempDir, clusterState, userConfig)

            val sshConfig = File(tempDir.toFile(), "sshConfig")
            assertThat(sshConfig).exists()
            val content = sshConfig.readText()
            assertThat(content).contains("StrictHostKeyChecking=no")
            assertThat(content).contains("User ubuntu")
            assertThat(content).contains("IdentityFile $TEST_SSH_KEY_PATH")
        }

        @Test
        fun `should include host aliases in sshConfig`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            service.writeSshAndEnvironmentFiles(tempDir, clusterState, userConfig)

            val sshConfig = File(tempDir.toFile(), "sshConfig")
            val content = sshConfig.readText()
            assertThat(content).contains("Host db0")
            assertThat(content).contains("Hostname 1.1.1.1")
        }

        @Test
        fun `should create env file with cluster metadata`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            service.writeSshAndEnvironmentFiles(tempDir, clusterState, userConfig)

            val envFile = File(tempDir.toFile(), "env.sh")
            assertThat(envFile).exists()
            val content = envFile.readText()
            assertThat(content).contains("CLUSTER_NAME=\"test-cluster-cluster-123\"")
        }

        @Test
        fun `an ssm route sends every host through its own SSM session`() {
            val clusterState =
                createClusterState(
                    controlHosts = listOf(ClusterHost("2.2.2.2", "10.0.0.2", "control0", "us-west-2a", "i-control")),
                )

            ssmService.writeSshAndEnvironmentFiles(tempDir, clusterState, createUserConfig())

            val lines = File(tempDir.toFile(), "sshConfig").readLines()
            assertThat(hostBlock(lines, "db0")).containsExactly("Host db0", " Hostname 1.1.1.1", proxyLine("i-12345"))
            assertThat(hostBlock(lines, "control0")).contains(proxyLine("i-control"))
        }

        @Test
        fun `an ssm route keeps idle sessions alive with keepalives that apply to every host`() {
            ssmService.writeSshAndEnvironmentFiles(tempDir, createClusterState(), createUserConfig())

            val lines = File(tempDir.toFile(), "sshConfig").readLines()
            val firstHost = lines.indexOfFirst { it.startsWith("Host ") }
            // Global, so before any Host block: Session Manager drops a session after 20 idle minutes.
            assertThat(lines.subList(0, firstHost)).contains("ServerAliveInterval 30", "ServerAliveCountMax 3")
        }

        /** Keepalives start only after auth, so a session that passes no data needs its own bound. */
        @Test
        fun `an ssm route bounds the wait for the server's banner for every host`() {
            ssmService.writeSshAndEnvironmentFiles(tempDir, createClusterState(), createUserConfig())

            val lines = File(tempDir.toFile(), "sshConfig").readLines()
            val firstHost = lines.indexOfFirst { it.startsWith("Host ") }
            assertThat(lines.subList(0, firstHost)).contains("ConnectTimeout 30")
        }

        @Test
        fun `the env sh fallback config routes hosts the same way as sshConfig`() {
            ssmService.writeSshAndEnvironmentFiles(tempDir, createClusterState(), createUserConfig())

            assertThat(File(tempDir.toFile(), "env.sh").readLines()).contains(proxyLine("i-12345"))
        }

        @Test
        fun `a direct route leaves hosts without a ProxyCommand`() {
            service.writeSshAndEnvironmentFiles(tempDir, createClusterState(), createUserConfig())

            assertThat(File(tempDir.toFile(), "sshConfig").readText()).doesNotContain("ProxyCommand")
            assertThat(File(tempDir.toFile(), "sshConfig").readText()).doesNotContain("ServerAlive")
            assertThat(File(tempDir.toFile(), "sshConfig").readText()).doesNotContain("ConnectTimeout")
            assertThat(File(tempDir.toFile(), "env.sh").readText()).doesNotContain("ProxyCommand")
        }

        @Test
        fun `an ssm route refuses a host with no instance ID and writes no sshConfig`() {
            val clusterState =
                createClusterState(
                    cassandraHosts = listOf(ClusterHost("1.1.1.1", "10.0.0.1", "db0", "us-west-2a", instanceId = "")),
                )

            assertThatThrownBy {
                ssmService.writeSshAndEnvironmentFiles(tempDir, clusterState, createUserConfig())
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("db0")
            assertThat(File(tempDir.toFile(), "sshConfig")).doesNotExist()
        }

        private fun proxyLine(instanceId: String) =
            " ProxyCommand /profiles/lab/edl-ssm-proxy aws ssm start-session --target $instanceId --document-name AWS-StartSSHSession " +
                "--parameters portNumber=%p --region us-west-2 --profile lab-profile"

        /** The lines of one `Host` block, from its `Host` line up to the blank line that ends it. */
        private fun hostBlock(
            lines: List<String>,
            alias: String,
        ): List<String> = lines.dropWhile { it != "Host $alias" }.takeWhile { it.isNotBlank() }
    }

    @Nested
    inner class WriteStressEnvironmentVariables {
        @Test
        fun `should create environment file with Cassandra host`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            val result = service.writeStressEnvironmentVariables(tempDir, clusterState, userConfig)

            assertThat(result.isSuccess).isTrue()
            val envFile = File(tempDir.toFile(), "environment.sh")
            assertThat(envFile).exists()
            val content = envFile.readText()
            assertThat(content).contains("CASSANDRA_EASY_STRESS_CASSANDRA_HOST=10.0.0.1")
        }

        @Test
        fun `should include datacenter from initConfig`() {
            val initConfig =
                createInitConfig(region = "eu-west-1")
            val clusterState = createClusterState(initConfig = initConfig)
            val userConfig = createUserConfig()

            service.writeStressEnvironmentVariables(tempDir, clusterState, userConfig)

            val envFile = File(tempDir.toFile(), "environment.sh")
            val content = envFile.readText()
            assertThat(content).contains("CASSANDRA_EASY_STRESS_DEFAULT_DC=eu-west-1")
        }

        @Test
        fun `should fallback to userConfig region when initConfig not available`() {
            val clusterState = createClusterState(initConfig = null)
            val userConfig = createUserConfig(region = "ap-southeast-1")

            service.writeStressEnvironmentVariables(tempDir, clusterState, userConfig)

            val envFile = File(tempDir.toFile(), "environment.sh")
            val content = envFile.readText()
            assertThat(content).contains("CASSANDRA_EASY_STRESS_DEFAULT_DC=ap-southeast-1")
        }

        @Test
        fun `should skip writing when no Cassandra hosts`() {
            val clusterState = createClusterState(cassandraHosts = emptyList())
            val userConfig = createUserConfig()

            val result = service.writeStressEnvironmentVariables(tempDir, clusterState, userConfig)

            assertThat(result.isSuccess).isTrue()
            val envFile = File(tempDir.toFile(), "environment.sh")
            assertThat(envFile).doesNotExist()
        }

        @Test
        fun `should set prometheus port to 0`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            service.writeStressEnvironmentVariables(tempDir, clusterState, userConfig)

            val envFile = File(tempDir.toFile(), "environment.sh")
            val content = envFile.readText()
            assertThat(content).contains("CASSANDRA_EASY_STRESS_PROM_PORT=0")
        }
    }

    @Nested
    inner class WriteAxonOpsWorkbenchConfig {
        @Test
        fun `should create JSON config file`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            val result = service.writeAxonOpsWorkbenchConfig(tempDir, clusterState, userConfig)

            assertThat(result.isSuccess).isTrue()
            val configFile = File(tempDir.toFile(), "axonops-workbench.json")
            assertThat(configFile).exists()
        }

        @Test
        fun `should include Cassandra host information`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            service.writeAxonOpsWorkbenchConfig(tempDir, clusterState, userConfig)

            val configFile = File(tempDir.toFile(), "axonops-workbench.json")
            val content = configFile.readText()
            // Private IP for hostname
            assertThat(content).contains("10.0.0.1")
            // Public IP for SSH host
            assertThat(content).contains("1.1.1.1")
        }

        @Test
        fun `should include SSH key path`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()
            val customKeyPath = "/custom/key/path"
            whenever(userConfigProvider.sshKeyPath).thenReturn(customKeyPath)

            service.writeAxonOpsWorkbenchConfig(tempDir, clusterState, userConfig)

            val configFile = File(tempDir.toFile(), "axonops-workbench.json")
            val content = configFile.readText()
            assertThat(content).contains(customKeyPath)
        }

        @Test
        fun `should output message on success`() {
            val clusterState = createClusterState()
            val userConfig = createUserConfig()

            service.writeAxonOpsWorkbenchConfig(tempDir, clusterState, userConfig)

            assertThat(capturedEvents.map { it.event.toDisplayString() })
                .anyMatch { it.contains("AxonOps Workbench configuration written") }
        }

        @Test
        fun `should skip writing when no Cassandra hosts`() {
            val clusterState = createClusterState(cassandraHosts = emptyList())
            val userConfig = createUserConfig()

            val result = service.writeAxonOpsWorkbenchConfig(tempDir, clusterState, userConfig)

            assertThat(result.isSuccess).isTrue()
            val configFile = File(tempDir.toFile(), "axonops-workbench.json")
            assertThat(configFile).doesNotExist()
        }
    }

    /**
     * Helper to create ClusterState for testing.
     */
    private fun createClusterState(
        cassandraHosts: List<ClusterHost> =
            listOf(
                ClusterHost(
                    publicIp = "1.1.1.1",
                    privateIp = "10.0.0.1",
                    alias = "db0",
                    availabilityZone = "us-west-2a",
                    instanceId = "i-12345",
                ),
            ),
        stressHosts: List<ClusterHost> = emptyList(),
        controlHosts: List<ClusterHost> = emptyList(),
        initConfig: InitConfig? = createInitConfig(),
    ): ClusterState {
        val hosts = mutableMapOf<ServerType, List<ClusterHost>>()
        if (cassandraHosts.isNotEmpty()) {
            hosts[ServerType.Cassandra] = cassandraHosts
        }
        if (stressHosts.isNotEmpty()) {
            hosts[ServerType.Stress] = stressHosts
        }
        if (controlHosts.isNotEmpty()) {
            hosts[ServerType.Control] = controlHosts
        }

        return ClusterState(
            clusterId = "cluster-123",
            name = "test-cluster",
            hosts = hosts,
            initConfig = initConfig,
            versions = mutableMapOf(),
        )
    }

    /**
     * Helper to create InitConfig for testing.
     */
    private fun createInitConfig(region: String = "us-west-2"): InitConfig =
        InitConfig(
            cassandraInstances = 1,
            stressInstances = 0,
            instanceType = "m5.large",
            stressInstanceType = "m5.large",
            region = region,
            name = "test-cluster",
            ebsType = "NONE",
            ebsSize = 100,
            ebsIops = 0,
            ebsThroughput = 0,
            controlInstances = 0,
            controlInstanceType = "m5.large",
        )

    /**
     * Helper to create User config for testing.
     */
    private fun createUserConfig(region: String = "us-west-2"): User =
        User(
            awsAccessKey = "test-access-key",
            awsSecret = "test-secret",
            region = region,
            email = "test@example.com",
            keyName = "test-key",
            awsProfile = "",
        )
}
