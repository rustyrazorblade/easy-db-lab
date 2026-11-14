package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.configuration.User
import java.io.File
import java.nio.file.Files

/**
 * Factory for creating test Context instances.
 * This replaces the testContext() method that was previously in the Context companion object.
 */
object TestContextFactory {
    /**
     * Creates a Context instance configured for testing.
     * Creates a temporary directory and a fake user configuration.
     *
     * @param tempDir JUnit @TempDir directory for test isolation (automatically cleaned up)
     * @param workingDirectory Optional working directory for lab operations.
     *                         Use JUnit @TempDir to provide an isolated directory for tests
     *                         that perform file operations (like Clean).
     *                         If null, defaults to the test temp directory.
     */
    fun createTestContext(
        tempDir: File,
        workingDirectory: File? = null,
    ): Context {
        val testTempDirectory = Files.createTempDirectory(tempDir.toPath(), "easydblab")
        assert(testTempDirectory != null)

        // Create a default profile with a fake user
        val user =
            User(
                email = "test@rustyrazorblade.com",
                region = "us-west-2",
                keyName = "test",
                awsProfile = "test",
                awsAccessKey = "test",
                awsSecret = "test",
                axonOpsOrg = "",
                axonOpsKey = "",
            )

        // An install directory whose packaged cassandra_versions.yaml declares nothing, so a test
        // that does not care about declared versions sees none.
        val appHome = Files.createDirectories(testTempDirectory.resolve("app/packer/cassandra"))
        Files.writeString(appHome.resolve("cassandra_versions.yaml"), "[]\n")

        val context =
            Context(
                easyDbLabUserDirectory = testTempDirectory.toFile(),
                workingDirectory = workingDirectory ?: testTempDirectory.toFile(),
                appHome = testTempDirectory.resolve("app").toString(),
            )

        // userConfigFile is accessed through the profileDir
        val userConfigFile = File(context.profileDir, "settings.yaml")
        context.yaml.writeValue(userConfigFile, user)

        return context
    }
}
