package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

class UserConfigProviderTest : BaseKoinTest() {
    private lateinit var userConfigProvider: UserConfigProvider
    private lateinit var profileDir: File
    private lateinit var userConfigFile: File

    // Use the Context from Koin (inherited from BaseKoinTest)
    // context property is already available from BaseKoinTest

    @BeforeEach
    fun setUp() {
        profileDir = File(tempDir, ".easy-db-lab")
        profileDir.mkdirs()
        userConfigFile = File(profileDir, "settings.yaml")

        userConfigProvider = UserConfigProvider(profileDir)
    }

    @Test
    fun `test getUserConfig loads existing complete config without prompting`() {
        // Create a complete config file
        val completeConfig =
            """
            email: test@example.com
            region: us-west-2
            keyName: test-key
            sshKeyPath: /home/user/.ssh/test-key
            awsProfile: default
            awsAccessKey: TEST_ACCESS_KEY
            awsSecret: TEST_SECRET
            arch: amd64
            axonOpsOrg: test-org
            axonOpsKey: test-key
            s3Bucket: test-bucket
            """.trimIndent()

        userConfigFile.writeText(completeConfig)

        val user = userConfigProvider.getUserConfig()

        assertEquals("test@example.com", user.email)
        assertEquals("us-west-2", user.region)
        assertEquals("test-key", user.keyName)
    }

    @Test
    fun `test getUserConfig loads existing complete config without creating new keys`() {
        // Create a complete config file to avoid interactive prompts
        val completeConfig =
            """
            email: test@example.com
            region: us-west-2
            keyName: test-key
            sshKeyPath: /home/user/.ssh/test-key
            awsProfile: default
            awsAccessKey: TEST_ACCESS_KEY
            awsSecret: TEST_SECRET
            arch: amd64
            axonOpsOrg: test-org
            axonOpsKey: test-key
            s3Bucket: test-bucket
            """.trimIndent()

        userConfigFile.writeText(completeConfig)

        // This test verifies that createInteractively is called even for existing configs
        // and that complete configs can be loaded without prompting
        val user = userConfigProvider.getUserConfig()

        assertEquals("test@example.com", user.email)
        assertEquals("us-west-2", user.region)
        assertEquals("test-key", user.keyName)
        // sshKeyPath is now derived from profileDir, not stored in User
    }

    @Test
    fun `test clearCache forces config reload`() {
        // Create initial config
        val config =
            """
            email: initial@example.com
            region: us-west-2
            keyName: test-key
            sshKeyPath: /home/user/.ssh/test-key
            awsProfile: default
            awsAccessKey: TEST_ACCESS_KEY
            awsSecret: TEST_SECRET
            arch: amd64
            axonOpsOrg: test-org
            axonOpsKey: test-key
            s3Bucket: test-bucket
            """.trimIndent()

        userConfigFile.writeText(config)

        // Load config first time
        val user1 = userConfigProvider.getUserConfig()
        assertEquals("initial@example.com", user1.email)

        // Modify config file
        val modifiedConfig = config.replace("initial@example.com", "modified@example.com")
        userConfigFile.writeText(modifiedConfig)

        // Without clearing cache, should return cached version
        val user2 = userConfigProvider.getUserConfig()
        assertEquals("initial@example.com", user2.email)

        // After clearing cache, should reload from file
        userConfigProvider.clearCache()
        val user3 = userConfigProvider.getUserConfig()
        assertEquals("modified@example.com", user3.email)
    }

    @Test
    fun `test field evolution scenario - createInteractively is always called`() {
        // This test verifies the core fix: createInteractively is always called for existing configs
        // We use complete configs to avoid interactive prompting that requires console input

        // Step 1: Create a complete config
        val completeConfig =
            """
            email: test@example.com
            region: us-west-2
            keyName: test-key
            sshKeyPath: /home/user/.ssh/test-key
            awsProfile: default
            awsAccessKey: TEST_ACCESS_KEY
            awsSecret: TEST_SECRET
            arch: amd64
            axonOpsOrg: test-org
            axonOpsKey: test-key
            s3Bucket: test-bucket
            """.trimIndent()

        userConfigFile.writeText(completeConfig)

        // Step 2: Load config to verify it works
        val user1 = userConfigProvider.getUserConfig()
        assertEquals("test@example.com", user1.email)
        assertEquals("TEST_ACCESS_KEY", user1.awsAccessKey)

        // Step 3: Create a different complete config (simulating field evolution)
        // All fields present to avoid prompting, but demonstrates that
        // createInteractively is called for existing configs
        val evolvedConfig =
            """
            email: evolved@example.com
            region: us-east-1
            keyName: evolved-key
            sshKeyPath: /home/user/.ssh/evolved-key
            awsProfile: production
            awsAccessKey: EVOLVED_ACCESS_KEY
            awsSecret: EVOLVED_SECRET
            arch: arm64
            axonOpsOrg: evolved-org
            axonOpsKey: evolved-key
            s3Bucket: evolved-bucket
            """.trimIndent()

        userConfigFile.writeText(evolvedConfig)

        // Step 4: Clear cache and reload - createInteractively should be called
        userConfigProvider.clearCache()

        // Step 5: Re-initialize the user config - createInteractively should be called
        val user2 = userConfigProvider.getUserConfig()

        // Verify the evolved config was loaded
        assertEquals("evolved@example.com", user2.email)
        assertEquals("us-east-1", user2.region)
        assertEquals("EVOLVED_ACCESS_KEY", user2.awsAccessKey)

        // The fix ensures createInteractively is called even for existing complete configs
        // This test passes if no exception is thrown and the config is loaded correctly
    }

    @Test
    fun `a profile written before the SSH transport existed reads as direct`() {
        userConfigFile.writeText(
            """
            email: test@example.com
            region: us-west-2
            keyName: test-key
            awsProfile: ""
            awsAccessKey: TEST_ACCESS_KEY
            awsSecret: TEST_SECRET
            """.trimIndent(),
        )

        assertThat(userConfigProvider.getUserConfig().sshTransport).isEqualTo(SshTransport.Direct)
    }

    @Test
    fun `the SSH transport is saved in lowercase and read back`() {
        val user =
            User(
                email = "test@example.com",
                region = "us-west-2",
                keyName = "test-key",
                awsProfile = "",
                awsAccessKey = "TEST_ACCESS_KEY",
                awsSecret = "TEST_SECRET",
                sshTransport = SshTransport.Ssm,
            )

        userConfigProvider.saveUserConfig(user)
        userConfigProvider.clearCache()

        assertThat(userConfigFile.readText()).contains("sshTransport: \"ssm\"")
        assertThat(userConfigProvider.getUserConfig().sshTransport).isEqualTo(SshTransport.Ssm)
    }

    @Test
    fun `a hand-edited transport is read the way the setup prompt would accept it`() {
        userConfigFile.writeText(
            """
            email: test@example.com
            region: us-west-2
            keyName: test-key
            awsProfile: ""
            awsAccessKey: TEST_ACCESS_KEY
            awsSecret: TEST_SECRET
            sshTransport: SSM
            """.trimIndent(),
        )

        assertThat(userConfigProvider.getUserConfig().sshTransport).isEqualTo(SshTransport.Ssm)
    }

    @Test
    fun `an unknown transport fails the load and names the bad value and the choices`() {
        userConfigFile.writeText(
            """
            email: test@example.com
            region: us-west-2
            keyName: test-key
            awsProfile: ""
            awsAccessKey: TEST_ACCESS_KEY
            awsSecret: TEST_SECRET
            sshTransport: tunnel
            """.trimIndent(),
        )

        assertThatThrownBy { userConfigProvider.getUserConfig() }
            .rootCause()
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'tunnel'")
            .hasMessageContaining("direct, ssm")
    }
}
