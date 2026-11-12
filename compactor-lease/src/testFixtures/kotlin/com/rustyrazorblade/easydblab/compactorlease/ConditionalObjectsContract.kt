package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The behavior every [ConditionalObjects] adapter must share. The lease is built only on these
 * operations, so an adapter that lets two writers both win, or lets a stale version overwrite the
 * current one, breaks the one-compactor-per-store guarantee.
 *
 * A subclass supplies the adapter; each test uses its own key, so one store can serve every test.
 */
abstract class ConditionalObjectsContract {
    private companion object {
        const val WRITERS = 8
        const val RACE_TIMEOUT_SECONDS = 30L
    }

    /** The adapter under test. */
    protected abstract fun store(): ConditionalObjects

    private fun key(): String = "contract/${UUID.randomUUID()}.json"

    @Test
    fun `reading a missing object returns nothing`() {
        assertThat(store().read(key())).isNull()
    }

    @Test
    fun `creating an absent object stores it with the returned version`() {
        val key = key()

        val created = store().createIfAbsent(key, "first")

        assertThat(created).isInstanceOf(CreateResult.Created::class.java)
        val version = (created as CreateResult.Created).version
        assertThat(store().read(key)).isEqualTo(VersionedObject("first", version))
    }

    @Test
    fun `of writers that race to create one key exactly one creates it`() {
        val key = key()
        val store = store()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(WRITERS)
        val results =
            try {
                val futures =
                    (1..WRITERS).map { writer ->
                        pool.submit<CreateResult> {
                            start.await()
                            store.createIfAbsent(key, "writer-$writer")
                        }
                    }
                start.countDown()
                futures.map { it.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        val winners = results.filterIsInstance<CreateResult.Created>()
        assertThat(winners).hasSize(1)
        assertThat(results.filterNot { it is CreateResult.Created }).hasSize(WRITERS - 1).allMatch { it == CreateResult.AlreadyExists }
        assertThat(store.read(key)?.version).isEqualTo(winners.single().version)
    }

    @Test
    fun `creating an object that exists leaves it unchanged`() {
        val key = key()
        val first = store().createIfAbsent(key, "first") as CreateResult.Created

        val second = store().createIfAbsent(key, "second")

        assertThat(second).isEqualTo(CreateResult.AlreadyExists)
        assertThat(store().read(key)).isEqualTo(VersionedObject("first", first.version))
    }

    @Test
    fun `a matching version replaces the object with a new version`() {
        val key = key()
        val created = store().createIfAbsent(key, "first") as CreateResult.Created

        val replaced = store().replaceIfVersionMatches(key, "second", created.version)

        assertThat(replaced).isInstanceOf(ReplaceResult.Replaced::class.java)
        val version = (replaced as ReplaceResult.Replaced).version
        assertThat(version).isNotEqualTo(created.version)
        assertThat(store().read(key)).isEqualTo(VersionedObject("second", version))
    }

    @Test
    fun `a stale version cannot replace the object`() {
        val key = key()
        val created = store().createIfAbsent(key, "first") as CreateResult.Created
        val current = store().replaceIfVersionMatches(key, "second", created.version) as ReplaceResult.Replaced

        val stale = store().replaceIfVersionMatches(key, "third", created.version)

        assertThat(stale).isEqualTo(ReplaceResult.VersionMismatch)
        assertThat(store().read(key)).isEqualTo(VersionedObject("second", current.version))
    }

    @Test
    fun `replacing a missing object is a version mismatch and creates nothing`() {
        val key = key()
        val elsewhere = store().createIfAbsent(key(), "other") as CreateResult.Created

        val result = store().replaceIfVersionMatches(key, "content", elsewhere.version)

        assertThat(result).isEqualTo(ReplaceResult.VersionMismatch)
        assertThat(store().read(key)).isNull()
    }
}
