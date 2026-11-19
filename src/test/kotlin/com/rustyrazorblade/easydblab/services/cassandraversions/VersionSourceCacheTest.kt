package com.rustyrazorblade.easydblab.services.cassandraversions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Instant

/** The profile cache of the ASF and cassandra-builds lists. */
class VersionSourceCacheTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-11-16T10:00:00Z")
    private var fetches = 0

    private fun cache() =
        VersionSourceCache(
            file = File(dir, "cassandra-sources/asf.json"),
            sourceName = "Apache archive",
            url = "https://archive.example/",
            serializer = AsfReleases.serializer(),
            clock = { now },
        )

    private fun fetching(vararg names: String): () -> AsfReleases =
        {
            fetches++
            AsfReleases(names.toList())
        }

    private val failing: () -> AsfReleases = {
        fetches++
        throw IOException("connection refused")
    }

    @Test
    fun `no file fetches and writes it, with the fetch time`() {
        val got = cache().get(fetch = fetching("5.0.9"))

        assertThat(got).isEqualTo(Cached(AsfReleases(listOf("5.0.9")), now, stale = false))
        assertThat(File(dir, "cassandra-sources/asf.json")).exists()
        assertThat(fetches).isEqualTo(1)
    }

    @Test
    fun `a file makes no fetch, and keeps its fetch time`() {
        val fetchedAt = now
        cache().get(fetch = fetching("5.0.9"))
        now = now.plusSeconds(86_400)

        val got = cache().get(fetch = failing)

        assertThat(got).isEqualTo(Cached(AsfReleases(listOf("5.0.9")), fetchedAt, stale = false))
        assertThat(fetches).isEqualTo(1)
    }

    @Test
    fun `refresh replaces the file`() {
        cache().get(fetch = fetching("5.0.9"))
        now = now.plusSeconds(60)

        val got = cache().get(refresh = true, fetch = fetching("5.0.9", "5.0.10"))

        assertThat(got.value.names).containsExactly("5.0.9", "5.0.10")
        assertThat(cache().get(fetch = failing).fetchedAt).isEqualTo(now)
    }

    @Test
    fun `a failed fetch with no file fails, naming the source and the URL`() {
        assertThatThrownBy { cache().get(fetch = failing) }
            .isInstanceOf(VersionSourceUnavailableException::class.java)
            .hasMessageContainingAll("Apache archive", "https://archive.example/", "connection refused")
    }

    @Test
    fun `a failed refresh with a file returns the file, marked stale`() {
        val fetchedAt = now
        cache().get(fetch = fetching("5.0.9"))
        now = now.plusSeconds(60)

        val got = cache().get(refresh = true, fetch = failing)

        assertThat(got).isEqualTo(Cached(AsfReleases(listOf("5.0.9")), fetchedAt, stale = true))
    }

    @Test
    fun `a cache file that cannot be read is fetched again`() {
        File(dir, "cassandra-sources").mkdirs()
        File(dir, "cassandra-sources/asf.json").writeText("{not json")

        val got = cache().get(fetch = fetching("4.1.7"))

        assertThat(got.value.names).containsExactly("4.1.7")
        assertThat(fetches).isEqualTo(1)
    }
}
