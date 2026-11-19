package com.rustyrazorblade.easydblab.providers.aws.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

internal class AMITest {
    @Test
    fun `compareTo should sort AMIs by creation date descending`() {
        val older =
            AMI(
                id = "ami-111",
                name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101",
                architecture = "amd64",
                creationDate = Instant.parse("2024-01-01T00:00:00Z"),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = listOf("snap-111"),
            )

        val newer =
            AMI(
                id = "ami-222",
                name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240102",
                architecture = "amd64",
                creationDate = Instant.parse("2024-01-02T00:00:00Z"),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = listOf("snap-222"),
            )

        // Newer AMIs should be "less than" older AMIs to support reverse chronological sorting
        assertThat(newer).isLessThan(older)

        val sorted = listOf(older, newer).sorted()
        assertThat(sorted).containsExactly(newer, older)
    }

    @Test
    fun `should extract AMI type from name`() {
        val cassandraAMI =
            AMI(
                id = "ami-111",
                name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101",
                architecture = "amd64",
                creationDate = Instant.now(),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = emptyList(),
            )

        val baseAMI =
            AMI(
                id = "ami-222",
                name = "rustyrazorblade/images/easy-db-lab-base-arm64-20240101",
                architecture = "arm64",
                creationDate = Instant.now(),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = emptyList(),
            )

        assertThat(cassandraAMI.type).isEqualTo("cassandra")
        assertThat(baseAMI.type).isEqualTo("base")
    }

    @Test
    fun `should identify private AMIs correctly`() {
        val privateAMI =
            AMI(
                id = "ami-111",
                name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101",
                architecture = "amd64",
                creationDate = Instant.now(),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = emptyList(),
            )

        val publicAMI =
            AMI(
                id = "ami-222",
                name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101",
                architecture = "amd64",
                creationDate = Instant.now(),
                ownerId = "123456789012",
                isPublic = true,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = emptyList(),
            )

        assertThat(privateAMI.isPublic).isFalse()
        assertThat(publicAMI.isPublic).isTrue()
    }

    @Test
    fun `should group AMIs by type and architecture`() {
        val ami1 =
            AMI(
                id = "ami-111",
                name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101",
                architecture = "amd64",
                creationDate = Instant.parse("2024-01-01T00:00:00Z"),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = emptyList(),
            )

        val ami2 =
            AMI(
                id = "ami-222",
                name = "rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240102",
                architecture = "amd64",
                creationDate = Instant.parse("2024-01-02T00:00:00Z"),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = emptyList(),
            )

        val ami3 =
            AMI(
                id = "ami-333",
                name = "rustyrazorblade/images/easy-db-lab-cassandra-arm64-20240101",
                architecture = "arm64",
                creationDate = Instant.parse("2024-01-01T00:00:00Z"),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = emptyList(),
            )

        val ami4 =
            AMI(
                id = "ami-444",
                name = "rustyrazorblade/images/easy-db-lab-base-amd64-20240101",
                architecture = "amd64",
                creationDate = Instant.parse("2024-01-01T00:00:00Z"),
                ownerId = "123456789012",
                isPublic = false,
                state = AMI.AVAILABLE_STATE,
                snapshotIds = emptyList(),
            )

        val amis = listOf(ami1, ami2, ami3, ami4)

        val grouped = amis.groupBy { it.groupKey }

        assertThat(grouped).hasSize(3)
        assertThat(grouped["cassandra-amd64"]).containsExactlyInAnyOrder(ami1, ami2)
        assertThat(grouped["cassandra-arm64"]).containsExactlyInAnyOrder(ami3)
        assertThat(grouped["base-amd64"]).containsExactlyInAnyOrder(ami4)
    }
}
