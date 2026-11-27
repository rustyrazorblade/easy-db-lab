package com.rustyrazorblade.easydblab.configuration.profile

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsLocation
import com.rustyrazorblade.easydblab.configuration.profile.aws.LegacyAwsProfileReader
import com.rustyrazorblade.easydblab.configuration.profile.aws.LegacyAwsProfileReaderTest
import com.rustyrazorblade.easydblab.configuration.profile.aws.awsProfileSerializers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The profile store over a real profile directory: the file format, the silent conversion of the
 * earlier file, locked saves and redacted errors.
 */
class ProfileStoreTest {
    @TempDir
    lateinit var profileDir: File

    private fun store() = ProfileStore(profileDir, awsProfileSerializers, LegacyAwsProfileReader())

    private fun legacy(name: String) {
        val resource = "/com/rustyrazorblade/easydblab/configuration/profile/legacy/$name.yaml"
        File(profileDir, Constants.ConfigPaths.PROFILE_SETTINGS_FILE)
            .writeBytes(checkNotNull(javaClass.getResource(resource)).readBytes())
    }

    private val profileFile get() = File(profileDir, Constants.ConfigPaths.PROFILE_FILE)
    private val legacyFile get() = File(profileDir, Constants.ConfigPaths.PROFILE_SETTINGS_FILE)

    /**
     * Converting the earlier file takes the lock; a lock file that cannot be opened is the file the
     * failure names, so `profile show` reports it rather than a `settings.yaml` that reads fine.
     *
     * Control: throw a plain IllegalStateException for the lock file; the file check fails.
     */
    @Test
    fun `a lock file that cannot be opened is the file the failure names`() {
        legacy("aws-profile")
        val lockFile = File(profileDir, Constants.ConfigPaths.PROFILE_LOCK_FILE)
        check(lockFile.mkdir()) { "the lock path should become a directory" }

        assertThatThrownBy { store().load() }
            .isInstanceOfSatisfying(ProfileFileException::class.java) { e ->
                assertThat(e.file).isEqualTo(lockFile)
                assertThat(e.message).contains(lockFile.path)
            }
    }

    /** Control: drop `tailscale` from the encoded profile; the rows with a Tailscale block fail. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `round trip keeps every field`(
        name: String,
        profile: Profile,
    ) {
        store().replace(profile)
        assertThat(store().load()).describedAs(name).isEqualTo(profile)
    }

    /** Control: rename the `accountBucket` key; every golden comparison fails. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `the file format matches its golden file`(
        name: String,
        profile: Profile,
    ) {
        store().replace(profile)
        val written = profileFile.readText()
        if (System.getenv("UPDATE_GOLDEN") == "1") {
            val dir = Path.of("src/test/resources", GOLDEN_DIR)
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("$name.yaml"), written)
            return
        }
        val golden = checkNotNull(javaClass.getResource("/$GOLDEN_DIR/$name.yaml")) { "missing golden file for $name" }
        assertThat(written).isEqualTo(golden.readText())
    }

    /** Control: skip setting 0600 on the temp file; it keeps the umask mode and the test fails. */
    @Test
    fun `save writes owner only permissions`() {
        store().replace(profile(ssoProfile))
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(profileFile.toPath()))).isEqualTo("rw-------")
    }

    /**
     * Control: turn strict mode off in the kaml configuration; the misspelled key decodes to the blank
     * default and the load succeeds, so setup would create a second bucket.
     */
    @Test
    fun `unknown key fails the load naming the key and holding no file text`() {
        store().replace(profile(accessKey))
        profileFile.writeText(profileFile.readText().replace("accountBucket:", "accountBuket:"))
        val secret = accessKey.secretAccessKey

        assertThatThrownBy { store().load() }
            .isInstanceOfSatisfying(ProfileFileException::class.java) { e ->
                assertThat(e.file).isEqualTo(profileFile)
                assertThat(e.reason).contains("accountBuket").contains("accountBucket")
                assertThat(e.message).contains(profileFile.path).contains("accountBuket").doesNotContain(secret)
                assertThat(e.reason).doesNotContain(secret)
            }
    }

    /** Control: skip the strict check in loadForSetup; setup reads the blank default and returns normally. */
    @Test
    fun `setup load fails on an unknown key like load does`() {
        store().replace(profile(accessKey))
        profileFile.writeText(profileFile.readText().replace("accountBucket:", "accountBuket:"))

        assertThatThrownBy { store().loadForSetup() }
            .isInstanceOfSatisfying(ProfileFileException::class.java) { e ->
                assertThat(e.file).isEqualTo(profileFile)
                assertThat(e.reason).contains("accountBuket").contains("accountBucket")
                assertThat(e.message).contains("Fix or remove that key").doesNotContain("run 'easy-db-lab profile setup'")
            }
    }

    /**
     * A key's name is shown only when it is a short identifier (24 characters at most); anything else
     * could be a pasted secret. A 40-character AWS secret access key with a leading letter and no
     * `+` or `/` is an identifier by form, so only the length keeps it out.
     *
     * Control: widen the pattern back to 41 characters; the AWS-secret row shows the key and fails.
     * Print the key whatever it looks like; every hidden row fails.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownKeyNames")
    fun `an unknown key is named only when it is a short identifier`(
        description: String,
        key: String,
        shown: Boolean,
    ) {
        store().replace(profile(accessKey))
        profileFile.writeText(profileFile.readText().replace("accountBucket:", "$key:"))

        listOf({ store().load() }, { store().loadForSetup() }).forEach { read ->
            assertThatThrownBy { read() }
                .describedAs(description)
                .isInstanceOfSatisfying(ProfileFileException::class.java) { e ->
                    // The parent field (destination) is a known name, so it is always shown.
                    assertThat(e.reason).contains("field destination: ").contains("accountBucket")
                    assertThat(e.unknownKey).isTrue()
                    if (shown) {
                        assertThat(e.reason).contains("unknown key '$key'")
                    } else {
                        assertThat(e.reason).contains("an unknown key").doesNotContain(key)
                        assertThat(e.message).doesNotContain(key)
                    }
                }
        }
    }

    /**
     * An unknown key fails both reads wherever it is, even after a value that does not parse (an
     * unknown SSH transport earlier in the file): the key check never looks at a value. The reason
     * names the map that holds the key and where the key starts.
     *
     * Control: restore the old check that decodes strictly and stops at the first failure; with the
     * bad transport ahead of the key, loadForSetup returns normally and every row after `destination`
     * fails. Drop the sealed-class branch from UnknownKeyFinder; the credentials row fails.
     */
    @ParameterizedTest(name = "{1} under ''{2}'', bad transport={3}")
    @MethodSource("misspelledKeys")
    fun `an unknown key fails both reads naming its map and place whatever the values`(
        key: String,
        misspelled: String,
        parentPath: String,
        badTransport: Boolean,
    ) {
        store().replace(profile(accessKey, Tailscale("fixture-ts-id", "fixture-ts-secret"), AxonOps("fixture-org", "fixture-key")))
        val text =
            profileFile
                .readText()
                .let { if (badTransport) withBadTransportFirst(it) else it }
                .replace("\n$key:", "\n$misspelled:")
                .replace(" $key:", " $misspelled:")
        check(misspelled in text) { "the fixture should hold $misspelled" }
        profileFile.writeText(text)
        val line = text.lines().indexOfFirst { it.trimStart().startsWith("$misspelled:") } + 1
        val column = text.lines()[line - 1].indexOf(misspelled) + 1
        val field = if (parentPath.isEmpty()) "" else "field $parentPath: "

        listOf({ store().load() }, { store().loadForSetup() }).forEach { read ->
            assertThatThrownBy { read() }
                .isInstanceOfSatisfying(ProfileFileException::class.java) { e ->
                    assertThat(e.unknownKey).isTrue()
                    assertThat(e.reason).startsWith("line $line, column $column: $field").contains("unknown key '$misspelled'")
                    assertThat(e.reason).contains(key).doesNotContain("carrier-pigeon")
                }
        }
    }

    /**
     * The reported case: an unknown SSH transport ahead of a misspelled `accountBucket`. Setup must
     * stop at the key, or it reads the bucket as blank and creates a second one.
     *
     * Control: restore the strict decode that stops at the first failure; it fails on the transport,
     * ignores that failure, and loadForSetup returns normally.
     */
    @Test
    fun `setup load names a misspelled key that follows an unknown ssh transport`() {
        store().replace(profile(accessKey))
        profileFile.writeText(withBadTransportFirst(profileFile.readText()).replace("accountBucket:", "accountBuket:"))

        assertThatThrownBy { store().loadForSetup() }
            .isInstanceOfSatisfying(ProfileFileException::class.java) { e ->
                assertThat(e.reason).contains("unknown key 'accountBuket'")
                assertThat(e.message).contains("Fix or remove that key")
            }
    }

    /** [text] with the SSH transport set to an unknown value and moved to the top of the destination block. */
    private fun withBadTransportFirst(text: String): String {
        val moved =
            text
                .replace("  sshTransport: \"ssm\"\n", "")
                .replace("  type: \"aws\"\n", "  type: \"aws\"\n  sshTransport: \"carrier-pigeon\"\n")
        check(moved.indexOf("carrier-pigeon") < moved.indexOf("location:")) { "the bad transport should come first" }
        return moved
    }

    /** Control: drop the type from the error message; the assertion on the name fails. */
    @Test
    fun `unknown destination type fails naming the type`() {
        store().replace(profile(ssoProfile))
        profileFile.writeText(profileFile.readText().replace("type: \"aws\"", "type: \"gcp\""))
        assertThatThrownBy { store().load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'gcp'")
            .hasMessageContaining("aws")
    }

    /** Control: make loadForSetup decode the profile; it throws and setup gets nothing. */
    @ParameterizedTest
    @ValueSource(strings = ["legacy/bad-transport.yaml:settings.yaml", "current/bad-transport.yaml:profile.yaml"])
    fun `unknown ssh transport fails load naming value and choices, and setup still gets every value`(spec: String) {
        val (resource, target) = spec.split(":")
        File(profileDir, target).writeBytes(checkNotNull(javaClass.getResource("$FIXTURES/$resource")).readBytes())

        assertThat(store().isSetup()).isTrue()
        assertThatThrownBy { store().load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'carrier-pigeon'")
            .hasMessageContaining("direct, ssm")
        val setup = store().loadForSetup()
        assertThat(setup).containsEntry("sshTransport", "carrier-pigeon")
        assertThat(setup).containsEntry("region", "us-west-2")
        assertThat(setup).containsEntry("awsProfile", "fixture-sso")
        assertThat(setup).containsEntry("s3Bucket", "easy-db-lab-fixture-bucket")
        assertThat(setup).containsEntry("tailscaleClientSecret", "fixture-ts-secret")
    }

    /** Control: keep the parser's exception as the cause; its message quotes the line and the test fails. */
    @Test
    fun `parse error message holds no file text`() {
        store().replace(profile(accessKey))
        profileFile.writeText(
            profileFile.readText().replace("secretAccessKey: \"fixture-secret-value\"", "secretAccessKey: [fixture-secret-value"),
        )
        assertThatThrownBy { store().load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(profileFile.path)
            .satisfies({ assertThat(it.message).doesNotContain("fixture-secret-value") })
            .satisfies({
                assertThat(
                    generateSequence(it) { e -> e.cause }.joinToString { e ->
                        e.message.orEmpty()
                    },
                ).doesNotContain("fixture-secret-value")
            })
    }

    /** Control: keep the parser's exception as the cause; its message quotes the value and the test fails. */
    @Test
    fun `a field with the wrong form fails without its value`() {
        store().replace(profile(accessKey))
        profileFile.writeText(
            profileFile.readText().replace(
                "secretAccessKey: \"fixture-secret-value\"",
                "secretAccessKey:\n      nested: fixture-secret-value",
            ),
        )
        assertThatThrownBy { store().load() }
            .isInstanceOf(IllegalStateException::class.java)
            .satisfies({
                assertThat(
                    generateSequence(it) { e -> e.cause }.joinToString { e ->
                        e.message.orEmpty()
                    },
                ).doesNotContain("fixture-secret-value")
            })
    }

    /** Control: rewrite settings.yaml after conversion; the byte comparison fails. */
    @ParameterizedTest
    @ValueSource(strings = ["aws-profile", "access-key", "both", "half-tailscale", "half-axonops", "full"])
    fun `earlier file is converted into profile yaml and left byte identical`(fixture: String) {
        legacy(fixture)
        val before = legacyFile.readBytes()

        val loaded = store().load()

        assertThat(legacyFile.readBytes()).isEqualTo(before)
        assertThat(profileFile).exists()
        assertThat(loaded).isEqualTo(LegacyAwsProfileReader().migrate(LegacyAwsProfileReaderTest.legacyFixture(fixture)))
        // Setup's flat view of the converted file is the earlier file's view, for the keys a profile keeps.
        assertThat(LegacyAwsProfileReader().migrate(store().loadForSetup())).isEqualTo(loaded)
        // The converted file is what the next command reads, even with the earlier file gone.
        legacyFile.delete()
        assertThat(store().load()).isEqualTo(loaded)
    }

    /**
     * Converting the earlier file writes `profile.yaml`; a directory that cannot be written is
     * `profile.yaml` as the file the failure names, not a raw IOException. The lock file exists
     * already, so the lock opens and the failure is the migration write.
     *
     * Control: let the IOException escape `write`; the exception type check fails.
     */
    @Test
    fun `a profile directory that cannot be written fails the conversion naming profile yaml`() {
        legacy("aws-profile")
        File(profileDir, Constants.ConfigPaths.PROFILE_LOCK_FILE).createNewFile()
        val dir = profileDir.toPath()
        val original = Files.getPosixFilePermissions(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            // Root can write anywhere, so the failure cannot be triggered.
            assumeFalse(Files.isWritable(dir))

            assertThatThrownBy { store().load() }
                .isInstanceOfSatisfying(ProfileFileException::class.java) { e ->
                    assertThat(e.file).isEqualTo(profileFile)
                    assertThat(e.message).contains(profileFile.path)
                }
            assertThat(profileFile).doesNotExist()
        } finally {
            Files.setPosixFilePermissions(dir, original)
        }
    }

    /** Control: print a notice when converting; the captured stdout is not empty. */
    @Test
    fun `migration prints nothing`() {
        legacy("full")
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            store().load()
        } finally {
            System.setOut(original)
        }
        assertThat(captured.toString()).isEmpty()
    }

    /** Control: make isSetup true whenever settings.yaml exists; this fails. */
    @Test
    fun `earlier file without credentials is not set up`() {
        legacy("neither")
        assertThat(store().isSetup()).isFalse()
        assertThat(store().loadForSetup()).containsEntry("email", "someone@example.com").containsEntry("region", "us-west-2")
        assertThat(profileFile).doesNotExist()
    }

    /** Control: return a default profile when no file exists; isSetup is true and the test fails. */
    @Test
    fun `no file is not set up and setup gets nothing`() {
        assertThat(store().isSetup()).isFalse()
        assertThat(store().loadForSetup()).isEmpty()
        assertThatThrownBy { store().load() }.hasMessageContaining("profile setup")
    }

    /** Control: write profile.yaml in place instead of by rename; a concurrent load reads a partial file and fails. */
    @Test
    fun `concurrent first loads agree`() {
        legacy("full")
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results =
                (1..threads).map {
                    pool.submit<Profile> {
                        start.await()
                        store().load()
                    }
                }
            start.countDown()
            val loaded = results.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(loaded.toSet()).hasSize(1)
            assertThat(store().load()).isEqualTo(loaded.first())
        } finally {
            pool.shutdownNow()
        }
    }

    /** Control: make update write back the profile loaded before the lock; one field is lost. */
    @Test
    fun `two stores saving different fields keep both`() {
        store().replace(profile(ssoProfile).withAws { it.copy(keyName = "", accountBucket = "") })
        val first = store()
        val second = store()
        val stale = first.load()

        second.update { it.withAws { aws -> aws.copy(accountBucket = "new-bucket") } }
        first.update { it.withAws { aws -> aws.copy(keyName = "new-key") } }

        val saved = store().load().destination as AwsDestination
        assertThat(saved.keyName).isEqualTo("new-key")
        assertThat(saved.accountBucket).isEqualTo("new-bucket")
        assertThat((stale.destination as AwsDestination).accountBucket).isEmpty()
    }

    /** Control: drop the hold-count check; the file lock throws OverlappingFileLockException, which names no profile. */
    @Test
    fun `a save from inside a change fails with a clear error and keeps the file`() {
        store().replace(profile(ssoProfile))
        val before = profileFile.readText()
        val outer = store()

        assertThatThrownBy {
            outer.update { current ->
                store().update { it.copy(email = "inner") }
                current.copy(email = "outer")
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("from inside the change")
        assertThat(profileFile.readText()).isEqualTo(before)
        assertThat(store().update { it.copy(email = "after") }.email).isEqualTo("after")
    }

    /** Control: drop the file lock and keep only a per-store lock; concurrent read-modify-writes lose changes. */
    @Test
    fun `concurrent updates from many threads keep every change`() {
        store().replace(profile(ssoProfile))
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        try {
            (1..threads)
                .map { n -> pool.submit { store().update { it.copy(email = it.email + ",$n") } } }
                .forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertThat(
            store()
                .load()
                .email
                .split(",")
                .drop(1)
                .map { it.toInt() },
        ).containsExactlyInAnyOrderElementsOf(1..threads)
    }

    companion object {
        private const val GOLDEN_DIR = "com/rustyrazorblade/easydblab/configuration/profile/golden"
        private const val FIXTURES = "/com/rustyrazorblade/easydblab/configuration/profile"
        private val ssoProfile = AwsCredentials.AwsProfile("fixture-sso")
        private val accessKey = AwsCredentials.AccessKey("AKIAFIXTUREKEY", "fixture-secret-value")

        private fun profile(
            credentials: AwsCredentials,
            tailscale: Tailscale? = null,
            axonOps: AxonOps? = null,
        ) = Profile(
            email = "someone@example.com",
            destination =
                AwsDestination(
                    AwsLocation("us-west-2"),
                    credentials,
                    "easy-db-lab-fixture-key",
                    "easy-db-lab-fixture-bucket",
                    SshTransport.Ssm,
                ),
            tailscale = tailscale,
            axonOps = axonOps,
        )

        private fun Profile.withAws(change: (AwsDestination) -> AwsDestination) = copy(destination = change(destination as AwsDestination))

        /** Each row: what the key is, the key, and whether the error may name it. */
        @JvmStatic
        fun unknownKeyNames(): List<Arguments> {
            // The form of an AWS secret access key: 40 characters, here with a leading letter and no "+" or "/".
            val awsSecret = "wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY12"
            check(awsSecret.length == 40)
            return listOf(
                Arguments.of("a misspelled field", "accountBuket", true),
                Arguments.of("an identifier of 24 characters", "a" + "b".repeat(23), true),
                Arguments.of("an identifier of 25 characters", "a" + "b".repeat(24), false),
                Arguments.of("a 40-character AWS secret access key", awsSecret, false),
                Arguments.of("a Tailscale client secret", "tskey-client-kAbC123dEf456gHi789jKl012mNo345pQr678sTu901", false),
            )
        }

        /** Each row: the declared key, its misspelling, the dotted path of its map, and whether a bad transport precedes it. */
        @JvmStatic
        fun misspelledKeys(): List<Arguments> =
            listOf(
                Triple("axonOps", "axonOpz", ""),
                Triple("accountBucket", "accountBuket", "destination"),
                Triple("region", "regoin", "destination.location"),
                Triple("secretAccessKey", "secretAccesKey", "destination.credentials"),
                Triple("tag", "tagg", "tailscale"),
                Triple("key", "kee", "axonOps"),
            ).flatMap { (key, misspelled, parent) ->
                listOf(false, true).map { badTransport -> Arguments.of(key, misspelled, parent, badTransport) }
            }

        @JvmStatic
        fun profiles(): List<Arguments> {
            val tailscale = Tailscale("fixture-ts-id", "fixture-ts-secret", "tag:fixture")
            val axonOps = AxonOps("fixture-org", "fixture-axonops-key")
            return listOf(
                Arguments.of("aws-profile-bare", profile(ssoProfile)),
                Arguments.of("aws-profile-blocks", profile(ssoProfile, tailscale, axonOps)),
                Arguments.of("access-key-bare", profile(accessKey)),
                Arguments.of("access-key-blocks", profile(accessKey, tailscale, axonOps)),
                Arguments.of("half-blocks", profile(ssoProfile, Tailscale("fixture-ts-id", ""), AxonOps("", "fixture-axonops-key"))),
            )
        }
    }
}
