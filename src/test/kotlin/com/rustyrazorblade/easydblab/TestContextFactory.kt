package com.rustyrazorblade.easydblab

import java.io.File
import java.nio.file.Files

/**
 * Factory for creating test Context instances.
 * This replaces the testContext() method that was previously in the Context companion object.
 */
object TestContextFactory {
    /**
     * Creates a Context instance configured for testing.
     * Creates a temporary directory and writes [TestProfiles.profile] as its default profile's
     * `profile.yaml`, through the real store.
     *
     * @param tempDir JUnit @TempDir directory for test isolation (automatically cleaned up)
     * @param workingDirectory Optional working directory for lab operations.
     *                         Use JUnit @TempDir to provide an isolated directory for tests
     *                         that perform file operations (like Clean).
     *                         If null, a `workspace` directory beside the user directory.
     */
    fun createTestContext(
        tempDir: File,
        workingDirectory: File? = null,
    ): Context {
        val testTempDirectory = Files.createTempDirectory(tempDir.toPath(), "easydblab")
        assert(testTempDirectory != null)

        val context =
            Context(
                easyDbLabUserDirectory = testTempDirectory.toFile(),
                // Its own directory, apart from the user directory, so a file resolved against the
                // wrong root lands somewhere a test can tell apart.
                workingDirectory = workingDirectory ?: testTempDirectory.resolve("workspace").toFile().also { it.mkdirs() },
                appHome = testTempDirectory.resolve("app").toString(),
            )

        TestProfiles.storeWith(context.profileDir)

        return context
    }
}
