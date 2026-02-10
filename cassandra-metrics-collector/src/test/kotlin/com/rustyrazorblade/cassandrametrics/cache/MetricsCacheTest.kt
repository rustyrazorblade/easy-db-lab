package com.rustyrazorblade.cassandrametrics.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetricsCacheTest {
    @Test
    fun `update and get returns cached value`() {
        val cache = MetricsCache()
        cache.update("thread_pools", "some_metric 42\n")

        assertThat(cache.get("thread_pools")).isEqualTo("some_metric 42\n")
    }

    @Test
    fun `get returns null for missing key`() {
        val cache = MetricsCache()

        assertThat(cache.get("nonexistent")).isNull()
    }

    @Test
    fun `update overwrites previous value`() {
        val cache = MetricsCache()
        cache.update("thread_pools", "old_value\n")
        cache.update("thread_pools", "new_value\n")

        assertThat(cache.get("thread_pools")).isEqualTo("new_value\n")
    }

    @Test
    fun `renderAll concatenates all entries sorted by key`() {
        val cache = MetricsCache()
        cache.update("caches", "cache_metric 1\n")
        cache.update("thread_pools", "thread_metric 2\n")
        cache.update("batch_metrics", "batch_metric 3\n")

        val result = cache.renderAll()

        assertThat(result).isEqualTo("batch_metric 3\ncache_metric 1\nthread_metric 2\n")
    }

    @Test
    fun `renderAll returns empty string when cache is empty`() {
        val cache = MetricsCache()

        assertThat(cache.renderAll()).isEmpty()
    }

    @Test
    fun `clear removes all entries`() {
        val cache = MetricsCache()
        cache.update("thread_pools", "data\n")
        cache.update("caches", "data\n")

        cache.clear()

        assertThat(cache.renderAll()).isEmpty()
        assertThat(cache.tableCount()).isZero()
    }

    @Test
    fun `tableCount returns number of cached tables`() {
        val cache = MetricsCache()

        assertThat(cache.tableCount()).isZero()

        cache.update("t1", "data\n")
        cache.update("t2", "data\n")

        assertThat(cache.tableCount()).isEqualTo(2)
    }
}
