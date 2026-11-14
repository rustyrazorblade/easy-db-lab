package com.rustyrazorblade.easydblab.providers.aws.model

import java.time.Instant

/**
 * Represents an Amazon Machine Image (AMI) with metadata used for pruning operations.
 *
 * This data class encapsulates all relevant AMI information needed to identify,
 * filter, and manage AMIs for cleanup operations. It implements Comparable to
 * support sorting AMIs by creation date (newest first).
 *
 * @property id The unique AMI identifier (e.g., "ami-123abc")
 * @property name The AMI name following pattern: rustyrazorblade/images/easy-db-lab-{type}-{arch}-{version}
 * @property architecture The CPU architecture (amd64 or arm64)
 * @property creationDate The timestamp when the AMI was created
 * @property ownerId The AWS account ID that owns this AMI
 * @property isPublic Whether the AMI is publicly accessible (true) or private (false)
 * @property snapshotIds List of EBS snapshot IDs associated with this AMI
 * @property state EC2 image state: `available`, `pending`, `failed` and so on
 */
data class AMI(
    val id: String,
    val name: String,
    val architecture: String,
    val creationDate: Instant,
    val ownerId: String,
    val isPublic: Boolean,
    val snapshotIds: List<String>,
    val state: String,
) : Comparable<AMI> {
    /** True when EC2 can launch from this image; a `pending` image is still being created. */
    val isAvailable: Boolean get() = state == AVAILABLE_STATE

    companion object {
        const val AVAILABLE_STATE = "available"

        // AMI name parsing constants
        // Pattern: rustyrazorblade/images/easy-db-lab-{type}-{arch}-{version}
        // After split by '-': [..., 'lab', 'cassandra', 'amd64', '20240101']
        private const val MIN_NAME_PARTS = 4 // Minimum parts: lab, type, arch, version
        private const val TYPE_OFFSET_FROM_END = 3 // Type is 3 positions from end
    }

    /**
     * Extracts the AMI type from the name.
     *
     * Expected name pattern: rustyrazorblade/images/easy-db-lab-{type}-{arch}-{version}
     * Example: rustyrazorblade/images/easy-db-lab-cassandra-amd64-20240101
     * Returns: "cassandra"
     */
    val type: String
        get() {
            val parts = name.split("-")
            // Pattern: rustyrazorblade/images/easy-db-lab-{type}-{arch}-{version}
            // After split by '-': [..., 'lab', 'cassandra', 'amd64', '20240101']
            return if (parts.size >= MIN_NAME_PARTS) {
                parts[parts.size - TYPE_OFFSET_FROM_END]
            } else {
                "unknown"
            }
        }

    /**
     * Generates a grouping key based on type and architecture.
     *
     * This key is used to group AMIs for pruning operations, ensuring we keep
     * the newest AMI for each combination of type and architecture.
     *
     * @return A string in format "{type}-{architecture}" (e.g., "cassandra-amd64")
     */
    val groupKey: String
        get() = "$type-$architecture"

    /**
     * Compares AMIs by creation date for sorting.
     *
     * Implements reverse chronological ordering (newest first) to simplify
     * pruning logic where we want to keep the most recent AMIs.
     *
     * @param other The AMI to compare against
     * @return Negative if this AMI is newer, positive if older, zero if equal
     */
    override fun compareTo(other: AMI): Int = other.creationDate.compareTo(this.creationDate)
}
