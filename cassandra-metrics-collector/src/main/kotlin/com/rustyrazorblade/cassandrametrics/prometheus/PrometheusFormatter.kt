package com.rustyrazorblade.cassandrametrics.prometheus

enum class MetricType {
    GAUGE,
    COUNTER,
}

data class MetricLine(
    val name: String,
    val labels: Map<String, String>,
    val value: Double,
)

object PrometheusFormatter {
    fun formatMetricFamily(
        name: String,
        help: String,
        type: MetricType,
        lines: List<MetricLine>,
    ): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("# HELP $name $help")
        sb.appendLine("# TYPE $name ${type.name.lowercase()}")
        for (line in lines) {
            sb.appendLine(formatLine(line))
        }
        return sb.toString()
    }

    private fun formatLine(line: MetricLine): String {
        val labelStr =
            if (line.labels.isEmpty()) {
                ""
            } else {
                line.labels.entries.joinToString(",", "{", "}") { (k, v) ->
                    "$k=\"${escapeLabel(v)}\""
                }
            }
        return "${line.name}$labelStr ${formatValue(line.value)}"
    }

    private fun escapeLabel(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

    private fun formatValue(value: Double): String =
        when {
            value == value.toLong().toDouble() && !value.isInfinite() -> value.toLong().toString()
            value.isNaN() -> "NaN"
            value.isInfinite() && value > 0 -> "+Inf"
            value.isInfinite() && value < 0 -> "-Inf"
            else -> value.toString()
        }
}
