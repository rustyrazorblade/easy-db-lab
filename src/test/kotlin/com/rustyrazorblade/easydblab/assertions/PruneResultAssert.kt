package com.rustyrazorblade.easydblab.assertions

import com.rustyrazorblade.easydblab.services.aws.AMIService.PruneResult
import org.assertj.core.api.AbstractObjectAssert
import org.assertj.core.api.Assertions.assertThat

/**
 * Custom AssertJ assertion class for PruneResult domain objects.
 *
 * Provides fluent, domain-specific assertions for testing AMI pruning operations.
 * Following the pattern recommended in docs/TESTING.md for domain-driven testing.
 *
 * Usage:
 * ```kotlin
 * import com.rustyrazorblade.easydblab.assertions.assertThat
 *
 * assertThat(result)
 *     .hasDeletedCount(3)
 *     .hasKeptCount(2)
 *     .deletedAMIsWithIds("ami-1", "ami-2", "ami-3")
 * ```
 */
class PruneResultAssert(
    actual: PruneResult,
) : AbstractObjectAssert<PruneResultAssert, PruneResult>(actual, PruneResultAssert::class.java) {
    fun hasDeletedCount(count: Int): PruneResultAssert {
        isNotNull
        assertThat(actual.deleted)
            .withFailMessage("Expected %d deleted AMIs but found %d: %s", count, actual.deleted.size, actual.deleted.map { it.id })
            .hasSize(count)
        return this
    }

    fun hasKeptCount(count: Int): PruneResultAssert {
        isNotNull
        assertThat(actual.kept)
            .withFailMessage("Expected %d kept AMIs but found %d: %s", count, actual.kept.size, actual.kept.map { it.id })
            .hasSize(count)
        return this
    }

    fun deletedAMIsWithIds(vararg ids: String): PruneResultAssert {
        isNotNull
        assertThat(actual.deleted.map { it.id })
            .withFailMessage(
                "Expected deleted AMIs with IDs %s but found %s",
                ids.toList(),
                actual.deleted.map { it.id },
            ).containsExactlyInAnyOrder(*ids)
        return this
    }

    fun keptAMIsWithIds(vararg ids: String): PruneResultAssert {
        isNotNull
        assertThat(actual.kept.map { it.id })
            .withFailMessage(
                "Expected kept AMIs with IDs %s but found %s",
                ids.toList(),
                actual.kept.map { it.id },
            ).containsExactlyInAnyOrder(*ids)
        return this
    }

    /**
     * Verifies deleted AMIs are in exact order (useful for testing sort order).
     */
    fun deletedAMIsInOrder(vararg ids: String): PruneResultAssert {
        isNotNull
        assertThat(actual.deleted.map { it.id })
            .withFailMessage(
                "Expected deleted AMIs in order %s but found %s",
                ids.toList(),
                actual.deleted.map { it.id },
            ).containsExactly(*ids)
        return this
    }

    /**
     * Verifies kept AMIs are in exact order (useful for testing sort order).
     */
    fun keptAMIsInOrder(vararg ids: String): PruneResultAssert {
        isNotNull
        assertThat(actual.kept.map { it.id })
            .withFailMessage(
                "Expected kept AMIs in order %s but found %s",
                ids.toList(),
                actual.kept.map { it.id },
            ).containsExactly(*ids)
        return this
    }

    fun hasNoDeleted(): PruneResultAssert {
        isNotNull
        assertThat(actual.deleted)
            .withFailMessage("Expected no deleted AMIs but found %s", actual.deleted.map { it.id })
            .isEmpty()
        return this
    }

    fun hasNoKept(): PruneResultAssert {
        isNotNull
        assertThat(actual.kept)
            .withFailMessage("Expected no kept AMIs but found %s", actual.kept.map { it.id })
            .isEmpty()
        return this
    }

    /**
     * Verifies that deleted AMIs are sorted by creation date ascending (oldest first).
     */
    fun deletedAMIsAreSortedByCreationDateAscending(): PruneResultAssert {
        isNotNull
        if (actual.deleted.size > 1) {
            for (i in 0 until actual.deleted.size - 1) {
                val current = actual.deleted[i]
                val next = actual.deleted[i + 1]
                assertThat(current.creationDate)
                    .withFailMessage(
                        "Expected deleted AMIs sorted by creation date ascending, but %s (%s) comes before %s (%s)",
                        current.id,
                        current.creationDate,
                        next.id,
                        next.creationDate,
                    ).isBeforeOrEqualTo(next.creationDate)
            }
        }
        return this
    }

    companion object {
        @JvmStatic
        fun assertThat(result: PruneResult): PruneResultAssert = PruneResultAssert(result)
    }
}

/**
 * Entry point for PruneResult assertions.
 *
 * Import this function to use fluent PruneResult assertions:
 * ```kotlin
 * import com.rustyrazorblade.easydblab.assertions.assertThat
 * ```
 */
fun assertThat(result: PruneResult): PruneResultAssert = PruneResultAssert.assertThat(result)
