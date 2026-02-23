package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventSerializationTest {
    private val domainEvents: List<Event> =
        listOf(
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
            Event.AwsSetup.Starting("Setting up..."),
            Event.Stress.JobStarting("stress-job"),
            Event.Service.Starting("cassandra", "node0"),
            Event.Provision.IamUpdating,
            Event.Command.ExecutionError("failed"),
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
