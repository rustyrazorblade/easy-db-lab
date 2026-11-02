package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.events.InvalidMetricName

/**
 * The metrics one `metrics stream` run streams, parsed from the raw `--metrics` value.
 *
 * It parses the raw string itself rather than through picocli's `split`, because picocli drops
 * empty entries and the stream must name them.  Positions are 1-based.
 */
sealed interface MetricSelection {
    /** The metrics to stream, each once, in the order first given. */
    data class Selected(
        val metrics: List<StreamMetric>,
    ) : MetricSelection

    /** Every entry that is empty or not in the catalogue. */
    data class Rejected(
        val entries: List<InvalidMetricName>,
    ) : MetricSelection

    companion object {
        private const val EMPTY = "empty name"
        private const val UNKNOWN = "unknown name"

        /** Parses [raw]; null (no `--metrics`) gives the defaults. */
        fun parse(raw: String?): MetricSelection {
            if (raw == null) return Selected(StreamMetric.defaults())
            val entries = raw.split(",").map { it.trim() }
            val invalid =
                entries.mapIndexedNotNull { index, name ->
                    when {
                        name.isEmpty() -> InvalidMetricName(index + 1, name, EMPTY)
                        StreamMetric.byName(name) == null -> InvalidMetricName(index + 1, name, UNKNOWN)
                        else -> null
                    }
                }
            return if (invalid.isEmpty()) {
                Selected(entries.mapNotNull { StreamMetric.byName(it) }.distinct())
            } else {
                Rejected(invalid)
            }
        }
    }
}
