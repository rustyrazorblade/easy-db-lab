package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventSerializationTest {
    private val domainEvents: List<Event> =
        listOf(
            // Original 18 domain interfaces
            Event.Cassandra.Starting("node0"),
            Event.K3s.ClusterStarting,
            Event.K8s.ManifestsApplying,
            Event.Infra.VpcCreating("test-vpc"),
            Event.Ec2.InstancesCreating(3, "Cassandra"),
            Event.Emr.ClusterCreating("test-cluster"),
            Event.OpenSearch.Creating("test-domain"),
            Event.S3.Uploading("file.txt", "s3://bucket/file.txt"),
            Event.Sqs.QueueCreating("test-queue"),
            Event.Grafana.DatasourcesCreating,
            Event.Backup.VictoriaMetricsStarting("s3://backup"),
            Event.Registry.CertGenerating("control0"),
            Event.Tailscale.DaemonStarting("control0"),
            Event.AwsSetup.Starting,
            Event.Stress.JobStarting("stress-job"),
            Event.Service.Starting("cassandra", "node0"),
            Event.Provision.IamUpdating,
            Event.Command.ExecutionError("failed"),
            // New domain interfaces added during Event.Message/Event.Error migration
            Event.Status.ClusterInfo(
                clusterId = "test-123",
                name = "test-cluster",
                createdAt = "2026-02-23",
                infrastructureStatus = "READY",
            ),
            Event.Status.NoClusterState,
            Event.Teardown.Starting,
            Event.Teardown.PreparingVpc("vpc-12345"),
            Event.Ami.PruningStarting("easy-db-lab-*", "cassandra", 3),
            Event.Ami.NoAmisToDelete,
            Event.ClickHouse.Deploying(2, 2, 4),
            Event.ClickHouse.CreatingPvs,
            Event.Docker.ContainerStarting("abc123"),
            Event.Docker.ContainerStartError("timeout"),
            Event.Mcp.ToolExecutionStarting("get_status"),
            Event.Mcp.ToolExecutionFailed("get_status", "not found"),
            Event.Logs.QueryInfo("cassandra", "1h", 100),
            Event.Logs.NoLogsFound,
            Event.Metrics.BackupComplete("s3://bucket/metrics"),
            Event.Metrics.NoControlNode,
            Event.Setup.ProfileAlreadyConfigured("default"),
            Event.Setup.ValidatingCredentials,
            Event.Ssh.ExecutingCommand("ls -la"),
            Event.Ssh.UploadingFile("/tmp/file", "10.0.1.5", "/opt/file"),
        )

    @Test
    fun `all domain events round-trip through JSON serialization`() {
        for (event in domainEvents) {
            val original =
                EventEnvelope(
                    event = event,
                    timestamp = "2026-02-23T10:15:30.123Z",
                    commandName = "test-command",
                )

            val json = EventEnvelope.toJson(original)
            val deserialized = EventEnvelope.fromJson(json)

            assertThat(deserialized)
                .describedAs("Round-trip failed for ${event::class.simpleName}")
                .isEqualTo(original)
            assertThat(json)
                .describedAs("Missing type discriminator for ${event::class.simpleName}")
                .contains("\"type\"")
        }
    }

    @Test
    fun `envelope round-trips through JSON`() {
        val original =
            EventEnvelope(
                event = Event.Message("Starting cassandra0"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = "start",
            )

        val json = EventEnvelope.toJson(original)
        val deserialized = EventEnvelope.fromJson(json)

        assertThat(deserialized).isEqualTo(original)
    }

    @Test
    fun `JSON contains type discriminator`() {
        val envelope =
            EventEnvelope(
                event = Event.Message("hello"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = null,
            )

        val json = EventEnvelope.toJson(envelope)

        assertThat(json).contains("\"type\"")
    }

    @Test
    fun `envelope with null commandName round-trips`() {
        val original =
            EventEnvelope(
                event = Event.Message("test"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = null,
            )

        val json = EventEnvelope.toJson(original)
        val deserialized = EventEnvelope.fromJson(json)

        assertThat(deserialized.commandName).isNull()
        assertThat(deserialized.event.toDisplayString()).isEqualTo("test")
    }

    @Test
    fun `toJson encodes short type names without package prefix`() {
        val envelope =
            EventEnvelope(
                event = Event.Ssh.ExecutingCommand("ls -la"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = "exec",
            )

        val json = EventEnvelope.toJson(envelope)

        assertThat(json).contains("\"type\":\"Ssh.ExecutingCommand\"")
        assertThat(json).doesNotContain("com.rustyrazorblade")
    }

    @Test
    fun `fromJson decodes short type names`() {
        val json =
            """
            |{"event":{"type":"Ssh.ExecutingCommand","command":"ls -la"},
            |"timestamp":"2026-02-23T10:15:30.123Z","commandName":null}
            """.trimMargin().replace("\n", "")

        val envelope = EventEnvelope.fromJson(json)

        assertThat(envelope.event).isInstanceOf(Event.Ssh.ExecutingCommand::class.java)
        assertThat((envelope.event as Event.Ssh.ExecutingCommand).command).isEqualTo("ls -la")
    }

    @Test
    fun `error event round-trips through JSON`() {
        val original =
            EventEnvelope(
                event = Event.Error("Something failed", "Details here"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = "up",
            )

        val json = EventEnvelope.toJson(original)
        val deserialized = EventEnvelope.fromJson(json)

        assertThat(deserialized).isEqualTo(original)
        assertThat(deserialized.event.isError()).isTrue()
    }
}
