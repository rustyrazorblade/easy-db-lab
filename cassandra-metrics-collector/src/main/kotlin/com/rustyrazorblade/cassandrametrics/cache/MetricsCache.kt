package com.rustyrazorblade.cassandrametrics.cache

import java.util.concurrent.ConcurrentHashMap

class MetricsCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun update(
        tableName: String,
        prometheusText: String,
    ) {
        cache[tableName] = prometheusText
    }

    fun get(tableName: String): String? = cache[tableName]

    fun renderAll(): String {
        val builder = StringBuilder()
        for (entry in cache.entries.sortedBy { it.key }) {
            builder.append(entry.value)
        }
        return builder.toString()
    }

    fun clear() {
        cache.clear()
    }

    fun tableCount(): Int = cache.size
}
