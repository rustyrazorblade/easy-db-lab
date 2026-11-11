package com.rustyrazorblade.easydblab.events

import kotlinx.serialization.Serializable

/**
 * One bad entry of `metrics stream --metrics`, carried by [Event.Metrics.InvalidNames].
 *
 * @property position The entry's 1-based position in the comma-separated list.
 * @property value The entry as given; empty for an empty entry.
 * @property reason Why the stream cannot use it.
 */
@Serializable
data class InvalidMetricName(
    val position: Int,
    val value: String,
    val reason: String,
)
