package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.StressJobService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for StressStart command.
 *
 * These tests verify stress job creation via StressJobService.
 */
class StressStartTest : BaseKoinTest() {
    private lateinit var mockStressJobService: StressJobService
    private lateinit var mockClusterStateManager: ClusterStateManager

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    private val testCassandraHost =
        ClusterHost(
            publicIp = "54.123.45.68",
            privateIp = "10.0.1.6",
            alias = "cassandra0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test124",
        )

    private val testCassandraHost2 =
        ClusterHost(
            publicIp = "54.123.45.69",
            privateIp = "10.0.1.7",
            alias = "cassandra1",
            availabilityZone = "us-west-2b",
            instanceId = "i-test125",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<StressJobService>().also {
                        mockStressJobService = it
                    }
                }

                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockStressJobService = getKoin().get()
        mockClusterStateManager = getKoin().get()
    }

    @Test
    fun `execute should fail when no control nodes exist`() {
        // Given - cluster state with no control nodes
        val emptyState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            )

        whenever(mockClusterStateManager.load()).thenReturn(emptyState)

        val command = StressStart()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute should fail when no Cassandra nodes exist`() {
        // Given - cluster state with control node but no Cassandra nodes
        val stateWithControlOnly =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControlOnly)

        val command = StressStart()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No Cassandra nodes found")
    }

    @Test
    fun `execute should fail when no stress args specified`() {
        // Given - cluster state with control node and Cassandra nodes
        val stateWithNodes =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testCassandraHost, testCassandraHost2),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithNodes)
        whenever(mockClusterStateManager.incrementStressJobCounter()).thenReturn(1)

        val command = StressStart()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Stress arguments are required")
    }

    @Test
    fun `execute should pass through workload args directly`() {
        // Given - cluster state with nodes
        val stateWithNodes =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testCassandraHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithNodes)
        whenever(mockClusterStateManager.incrementStressJobCounter()).thenReturn(1)
        whenever(mockStressJobService.startJob(any(), any()))
            .thenReturn(Result.success("job-created"))

        val command = StressStart()
        command.stressArgs = listOf("BasicTimeSeries", "-d", "1h", "--threads", "100")

        // When
        command.execute()

        // Then - verify startJob was called with passthrough args
        verify(mockStressJobService).startJob(
            controlHost = eq(testControlHost),
            config =
                argThat { config ->
                    config.args.contains("run") &&
                        config.args.contains("BasicTimeSeries") &&
                        config.args.contains("-d") &&
                        config.args.contains("1h") &&
                        config.args.contains("--threads") &&
                        config.args.contains("100")
                },
        )
    }

    @Test
    fun `execute should fail when startJob fails`() {
        // Given - cluster state with nodes
        val stateWithNodes =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testCassandraHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithNodes)
        whenever(mockClusterStateManager.incrementStressJobCounter()).thenReturn(1)
        whenever(mockStressJobService.startJob(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Job creation failed")))

        val command = StressStart()
        command.stressArgs = listOf("KeyValue")

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Job creation failed")
    }

    @Test
    fun `parseTags should parse comma-separated key=value pairs`() {
        val command = StressStart()
        val result = command.parseTags("env=production,team=platform")
        assertThat(result).containsEntry("env", "production")
        assertThat(result).containsEntry("team", "platform")
    }

    @Test
    fun `parseTags should reject a value containing whitespace`() {
        // The regression this guards: JAVA_TOOL_OPTIONS is one string the JVM splits on whitespace,
        // so "note=first run" produced "-Dotel.resource.attributes=...,note=first" followed by a
        // bare "run", and the JVM exited with "Unrecognized option: run" before cassandra-easy-
        // stress started. --tags "note=..." invites prose, so this is reachable by ordinary use.
        val command = StressStart()

        assertThatThrownBy { command.parseTags("note=first run") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("note")
            .hasMessageContaining("first run")
            .hasMessageContaining("whitespace")
    }

    @Test
    fun `parseTags should reject a key containing whitespace`() {
        val command = StressStart()

        assertThatThrownBy { command.parseTags("my note=value") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("my note")
    }

    @Test
    fun `parseTags should reject a fragment with no equals rather than dropping it`() {
        // A comma always separates tags, so "note=before,after" cannot mean a value with a comma in
        // it. The old code filtered "after" out silently and returned {note: before} — half the
        // value gone, no warning, and a run labelled with something other than what was typed.
        val command = StressStart()

        assertThatThrownBy { command.parseTags("note=before,after") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("after")
    }

    @Test
    fun `parseTags should reject a value containing an equals sign`() {
        // Reaches two different parsers that need not split on the same '='.
        val command = StressStart()

        assertThatThrownBy { command.parseTags("query=a=b") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("query")
    }

    @Test
    fun `parseTags should still accept ordinary tags, including spaces around separators`() {
        // The gate must not be so tight that normal input fails. A space after a comma is a normal
        // way to type a list and is trimmed, not rejected — only whitespace INSIDE a key or value
        // breaks anything.
        val command = StressStart()

        assertThat(command.parseTags("env=production, team=platform"))
            .containsEntry("env", "production")
            .containsEntry("team", "platform")
        assertThat(command.parseTags("build=5.0.9-rrb-j21-20260906-8ba1639-jdk21"))
            .containsEntry("build", "5.0.9-rrb-j21-20260906-8ba1639-jdk21")
        assertThat(command.parseTags("note=")).containsEntry("note", "")
    }

    @Test
    fun `execute should reject a bad tag before any job is created`() {
        // Failing at parse time is the requirement: a rejected tag must not leave a half-created
        // K8s Job behind for someone to clean up.
        val stateWithNodes =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testCassandraHost),
                    ),
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateWithNodes)
        whenever(mockClusterStateManager.incrementStressJobCounter()).thenReturn(1)

        val command = StressStart()
        command.stressArgs = listOf("KeyValue")
        command.tags = "note=first run"

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("whitespace")

        verify(mockStressJobService, never()).startJob(any(), any())
    }

    @Test
    fun `parseTags should return empty map for null input`() {
        val command = StressStart()
        val result = command.parseTags(null)
        assertThat(result).isEmpty()
    }

    @Test
    fun `parseTags should return empty map for blank input`() {
        val command = StressStart()
        val result = command.parseTags("")
        assertThat(result).isEmpty()
    }

    @Test
    fun `execute should pass tags to startJob`() {
        val stateWithNodes =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testCassandraHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithNodes)
        whenever(mockClusterStateManager.incrementStressJobCounter()).thenReturn(1)
        whenever(mockStressJobService.startJob(any(), any()))
            .thenReturn(Result.success("job-created"))

        val command = StressStart()
        command.stressArgs = listOf("KeyValue")
        command.tags = "env=test,team=qa"

        command.execute()

        verify(mockStressJobService).startJob(
            controlHost = eq(testControlHost),
            config = argThat { config -> config.tags["env"] == "test" && config.tags["team"] == "qa" },
        )
    }

    @Test
    fun `execute should auto-name from workload when no name provided`() {
        val stateWithNodes =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testCassandraHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithNodes)
        whenever(mockClusterStateManager.incrementStressJobCounter()).thenReturn(1)
        whenever(mockStressJobService.startJob(any(), any()))
            .thenReturn(Result.success("job-created"))

        val command = StressStart()
        command.stressArgs = listOf("KeyValue", "-d", "1h")

        command.execute()

        verify(mockStressJobService).startJob(
            controlHost = eq(testControlHost),
            config = argThat { config -> config.jobName == "keyvalue-1" && config.promPort == 9501 },
        )
    }

    @Test
    fun `execute should use user-supplied name without counter`() {
        val stateWithNodes =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testCassandraHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithNodes)
        whenever(mockClusterStateManager.incrementStressJobCounter()).thenReturn(3)
        whenever(mockStressJobService.startJob(any(), any()))
            .thenReturn(Result.success("job-created"))

        val command = StressStart()
        command.jobName = "my-test"
        command.stressArgs = listOf("KeyValue")

        command.execute()

        verify(mockStressJobService).startJob(
            controlHost = eq(testControlHost),
            config = argThat { config -> config.jobName == "my-test" && config.promPort == 9503 },
        )
    }

    @Test
    fun `extractWorkloadName should return lowercased workload from implicit run`() {
        val command = StressStart()
        assertThat(command.extractWorkloadName(listOf("KeyValue", "-d", "1h"))).isEqualTo("keyvalue")
    }

    @Test
    fun `extractWorkloadName should return lowercased workload from explicit run`() {
        val command = StressStart()
        assertThat(command.extractWorkloadName(listOf("run", "BasicTimeSeries"))).isEqualTo("basictimeseries")
    }

    @Test
    fun `extractWorkloadName should return stress for empty args`() {
        val command = StressStart()
        assertThat(command.extractWorkloadName(emptyList())).isEqualTo("stress")
    }
}
