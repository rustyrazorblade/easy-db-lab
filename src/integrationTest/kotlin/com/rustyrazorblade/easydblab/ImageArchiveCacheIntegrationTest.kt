package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readBytes

/**
 * Checks that [ImageArchiveCache] saves each image at most once and never runs two saves at once,
 * across threads and across JVMs, so the parallel test forks fetch each preload image once per run.
 */
class ImageArchiveCacheIntegrationTest {
    companion object {
        private const val IMAGE = "example/image:1.0"
        private const val CONTENT_ID = "sha256:0123abcd"
        private const val CONTENDERS = 6
        private const val PROCESS_TIMEOUT_SECONDS = 120L
    }

    /** A source that counts its saves and fails if two of them ever overlap. */
    private class CountingSource(
        private val id: String = CONTENT_ID,
    ) : ImageArchiveSource {
        val saves = AtomicInteger()
        private val inFlight = AtomicInteger()

        override fun contentId(image: String): String = id

        override fun save(
            image: String,
            target: Path,
        ) {
            check(inFlight.incrementAndGet() == 1) { "two saves ran at once" }
            saves.incrementAndGet()
            Thread.sleep(200)
            Files.writeString(target, "archive of $image $id")
            inFlight.decrementAndGet()
        }
    }

    @Test
    fun `threads asking for the same image get one save and the same archive`(
        @TempDir dir: Path,
    ) {
        val source = CountingSource()
        val cache = ImageArchiveCache(dir, source)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(CONTENDERS)
        val archives =
            try {
                (1..CONTENDERS)
                    .map {
                        pool.submit<Path> {
                            start.await()
                            cache.archive(IMAGE)
                        }
                    }.also { start.countDown() }
                    .map { it.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(source.saves.get()).isEqualTo(1)
        assertThat(archives.toSet()).hasSize(1)
        assertThat(archives.first().readBytes().decodeToString()).isEqualTo("archive of $IMAGE $CONTENT_ID")
    }

    @Test
    fun `a changed content ID is saved again, not served from the old archive`(
        @TempDir dir: Path,
    ) {
        val before = ImageArchiveCache(dir, CountingSource("sha256:1111")).archive(IMAGE)
        val changed = CountingSource("sha256:2222")

        val after = ImageArchiveCache(dir, changed).archive(IMAGE)

        assertThat(changed.saves.get()).isEqualTo(1)
        assertThat(after).isNotEqualTo(before)
        assertThat(after.readBytes().decodeToString()).isEqualTo("archive of $IMAGE sha256:2222")
    }

    @Test
    fun `JVMs asking for the same image at once get one save, never two at a time, and identical bytes`(
        @TempDir dir: Path,
    ) {
        val cacheDir = Files.createDirectories(dir.resolve("cache"))
        val go = dir.resolve("go")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val processes =
            (1..CONTENDERS).map { index ->
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    ImageArchiveCacheContender::class.java.name,
                    cacheDir.toString(),
                    go.toString(),
                    dir.resolve("saves.log").toString(),
                    dir.resolve("in-flight").toString(),
                    IMAGE,
                    CONTENT_ID,
                ).redirectErrorStream(true)
                    .redirectOutput(dir.resolve("contender-$index.out").toFile())
                    .start()
            }
        Files.createFile(go)

        val exits = processes.map { if (it.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) it.exitValue() else -1 }
        val outputs = (1..CONTENDERS).map { Files.readString(dir.resolve("contender-$it.out")).trim() }

        assertThat(exits).describedAs(outputs.joinToString("\n")).containsOnly(0)
        assertThat(Files.readAllLines(dir.resolve("saves.log"))).hasSize(1)
        assertThat(outputs.toSet()).hasSize(1)
    }

    @Test
    fun `the registry source fetches a real image archive that every request then shares`(
        @TempDir dir: Path,
    ) {
        val cache = ImageArchiveCache(dir, RegistryImageSource(dir.resolve("layers")))

        val first = cache.archive(K3sPreloadedImages.PAUSE_IMAGE)
        val second = cache.archive(K3sPreloadedImages.PAUSE_IMAGE)

        assertThat(second).isEqualTo(first)
        assertThat(DockerArchive.missingEntries(first)).isEmpty()
        assertThat(sha256(first)).isEqualTo(sha256(second))
        Files.newDirectoryStream(dir, "*.tar").use { archives -> assertThat(archives.toList()).containsExactly(first) }
    }

    private fun sha256(path: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.readBytes()))
}
