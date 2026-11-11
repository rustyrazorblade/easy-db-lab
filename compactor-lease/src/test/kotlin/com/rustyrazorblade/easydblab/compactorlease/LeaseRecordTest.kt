package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** The lease's body: the format `status` reads and every agent writes. */
class LeaseRecordTest {
    private val record =
        LeaseRecord(
            holder = "lab-a-1234/account-compactor-7d9f",
            sequence = 42,
            ttlSeconds = 120,
            renewedAt = Instant.parse("2026-11-07T18:00:00Z"),
        )

    @Test
    fun `a lease is written in the documented format`() {
        assertThat(record.toJson())
            .isEqualTo(
                """{"holder":"lab-a-1234/account-compactor-7d9f","sequence":42,"ttlSeconds":120,"renewedAt":"2026-11-07T18:00:00Z"}""",
            )
    }

    @Test
    fun `a lease round trips through its JSON`() {
        assertThat(LeaseRecord.fromJson(record.toJson())).isEqualTo(record)
    }

    /** A record an agent already wrote, with a sub-second renewal time as a real clock gives, reads back byte for byte. */
    @Test
    fun `a lease in the written format with a fractional renewal time reads back to the same bytes`() {
        val written = """{"holder":"h","sequence":3,"ttlSeconds":120,"renewedAt":"2026-11-07T18:00:01.123456Z"}"""

        val read = LeaseRecord.fromJson(written)

        assertThat(read.renewedAt.toString()).isEqualTo("2026-11-07T18:00:01.123456Z")
        assertThat(read.toJson()).isEqualTo(written)
    }

    @Test
    fun `a lease written by a newer agent with an extra field is still read`() {
        val json = """{"holder":"h","sequence":1,"ttlSeconds":120,"renewedAt":"2026-11-07T18:00:00Z","note":"x"}"""

        assertThat(LeaseRecord.fromJson(json).holder).isEqualTo("h")
    }

    @Test
    fun `the next record has the next sequence, and the new holder, TTL and renewal time`() {
        val later = Instant.parse("2026-11-07T18:05:00Z")

        val next = record.next(holder = "lab-b-5678/account-compactor-1a2b", ttlSeconds = 15, renewedAt = later)

        assertThat(next).isEqualTo(LeaseRecord("lab-b-5678/account-compactor-1a2b", 43, 15, later))
    }

    @Test
    fun `a blank holder is refused`() {
        assertThatThrownBy { record.copy(holder = " ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a negative sequence is refused`() {
        assertThatThrownBy { record.copy(sequence = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
