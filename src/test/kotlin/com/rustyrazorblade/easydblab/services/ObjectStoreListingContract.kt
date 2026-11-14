package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * How every [ObjectStore] lists a directory: by prefix and delimiter, nested or not, and empty.
 *
 * The unit tests store objects in [InMemoryObjectStore], so it must list the way `S3ObjectStore`
 * lists against real S3. One set of cases runs against both: `InMemoryObjectStoreTest` here, and
 * `S3ObjectStoreListingContractIntegrationTest` against LocalStack. Each case writes under its
 * own fresh directory ([freshRoot]), so the S3 run can share one bucket across cases.
 *
 * A non-recursive listing that holds both objects and directories is compared as a set: S3 returns
 * a page's objects and then its common prefixes, the in-memory store one key order. Within each
 * kind both are in key order.
 */
abstract class ObjectStoreListingContract {
    /** The store under test. */
    protected abstract val store: ObjectStore

    /** A directory no other case has written under. */
    protected abstract fun freshRoot(): ClusterStoragePath

    private lateinit var dir: ClusterStoragePath

    @BeforeEach
    fun freshDirectory() {
        dir = freshRoot()
    }

    private fun put(vararg relativeKeys: String) = relativeKeys.forEach { store.uploadContent(it, dir.resolve(it)) }

    /** The listed keys relative to this case's directory. */
    private fun relative(files: List<ObjectStore.FileInfo>) = files.map { it.path.getKey().removePrefix("${dir.getKey()}/") }

    @Test
    fun `a recursive listing returns every nested key in key order with its size`() {
        store.uploadContent("1", dir.resolve("a/1"))
        store.uploadContent("22", dir.resolve("a/b/2"))
        store.uploadContent("333", dir.resolve("a/b/c/3"))

        val listed = store.listFiles(dir.resolve("a"))

        assertThat(relative(listed)).containsExactly("a/1", "a/b/2", "a/b/c/3")
        assertThat(listed.map { it.size }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `a non-recursive listing returns direct children and one entry per nested directory`() {
        put("a/1", "a/2", "a/b/1", "a/b/c/2", "a/d/1")

        val listed = store.listFiles(dir.resolve("a"), recursive = false)

        assertThat(relative(listed)).containsExactlyInAnyOrder("a/1", "a/2", "a/b", "a/d")
        assertThat(relative(listed.filter { it.size > 0 })).containsExactly("a/1", "a/2")
        assertThat(relative(listed.filter { it.size == 0L })).containsExactly("a/b", "a/d")
    }

    @Test
    fun `a non-recursive listing of a directory holding only directories lists each once`() {
        put("tenants/acme/x", "tenants/acme/y", "tenants/beta/z")

        assertThat(relative(store.listFiles(dir.resolve("tenants"), recursive = false))).containsExactly("tenants/acme", "tenants/beta")
    }

    @Test
    fun `a listing never includes a sibling whose name begins with the directory's`() {
        put("acme/1", "acme-dev/1", "acmex")

        assertThat(relative(store.listFiles(dir.resolve("acme")))).containsExactly("acme/1")
        assertThat(relative(store.listFiles(dir.resolve("acme"), recursive = false))).containsExactly("acme/1")
    }

    @Test
    fun `startAfter skips every key up to and including it`() {
        put("a/1", "a/2", "a/3")

        assertThat(relative(store.listFiles(dir.resolve("a"), startAfter = dir.resolve("a/1").getKey())))
            .containsExactly("a/2", "a/3")
    }

    @Test
    fun `a directory with nothing under it lists empty`() {
        put("other/1")

        assertThat(store.listFiles(dir.resolve("missing"))).isEmpty()
        assertThat(store.listFiles(dir.resolve("missing"), recursive = false)).isEmpty()
    }

    @Test
    fun `an object is not listed as a directory of itself`() {
        put("a/file")

        assertThat(store.listFiles(dir.resolve("a/file"))).isEmpty()
    }
}
