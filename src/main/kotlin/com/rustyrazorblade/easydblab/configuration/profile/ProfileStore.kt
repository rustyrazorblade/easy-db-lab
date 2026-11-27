package com.rustyrazorblade.easydblab.configuration.profile

import com.charleskorn.kaml.MalformedYamlException
import com.charleskorn.kaml.MissingRequiredPropertyException
import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.UnknownPolymorphicTypeException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.writeTextAtomically
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerializationException
import kotlinx.serialization.modules.SerializersModule
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reads and saves the active profile's `profile.yaml`, converting the earlier `settings.yaml` on
 * first load.
 *
 * Nothing is cached across calls: every [load] reads the file, so a value another command saved is
 * seen at once. Every save is an [update]: under an exclusive lock on `profile.yaml.lock` it reads the
 * file again, applies the change and replaces the file in one rename, with mode 0600 because it holds
 * credentials. Two commands that save different settings at once keep both. A JVM cannot take a
 * second lock on a file it holds already, so threads of one process take turns on [localLock] first,
 * and a change must not call back into the store; a save inside a change fails at once with a clear
 * error. Reads take no lock: every save replaces the file in one rename, so a read sees the whole of
 * one save or the whole of the next.
 *
 * The earlier file is read, never written, moved or deleted, so a build of an earlier release keeps
 * working on the same profile. The store names no destination type: the [serializers] register each
 * destination, and [legacyReader] knows the earlier file's keys.
 *
 * Every failure is a [ProfileFileException] whose [ProfileFileRemedy] says whether `profile setup`
 * repairs it: only a value that does not decode does, since setup reads the raw values ([loadForSetup]).
 * A file that cannot be read or parsed, or that holds an unknown key, stops setup too.
 *
 * Parse errors never carry text from the file, which can hold an AWS secret, the AxonOps key or the
 * Tailscale client secret. They name the file, where parsing stopped and what kind of problem it was.
 * The one exception is an unknown key: its name is shown on purpose, but only when it is a short
 * identifier ([Constants.ConfigPaths.PROFILE_SHOWN_KEY_PATTERN]); a longer or odd key, which could be
 * a pasted secret, is reported as "an unknown key".
 *
 * Unknown keys are found by [UnknownKeyFinder] before any value is decoded, in [load] and
 * [loadForSetup] alike, so a bad value earlier in the file cannot hide a misspelled key after it.
 *
 * @param profileDir the active profile's directory
 */
class ProfileStore(
    val profileDir: File,
    serializers: SerializersModule,
    private val legacyReader: LegacyProfileReader,
) {
    /** The current profile file. */
    val file: File = File(profileDir, Constants.ConfigPaths.PROFILE_FILE)

    /** The earlier releases' profile file, read only to convert it. */
    val legacyFile: File = File(profileDir, Constants.ConfigPaths.PROFILE_SETTINGS_FILE)

    /** The private key of the profile's key pair; setup writes it when it creates the key pair. */
    val sshKeyPath: String
        get() = File(profileDir, Constants.ConfigPaths.PROFILE_SSH_KEY_FILE).absolutePath

    private val lockFile = File(profileDir, Constants.ConfigPaths.PROFILE_LOCK_FILE)

    private val unknownKeys = UnknownKeyFinder(serializers, Constants.ConfigPaths.PROFILE_TYPE_KEY)

    private val yaml =
        Yaml(
            serializersModule = serializers,
            configuration =
                YamlConfiguration(
                    // Strict: a misspelled key must fail the load. Decoding it to the blank default would
                    // read as "not created yet", and the next command would create a second bucket or key pair.
                    // UnknownKeyFinder reports such a key first; strict mode is the decoder's own guard behind it.
                    strictMode = true,
                    polymorphismStyle = PolymorphismStyle.Property,
                    polymorphismPropertyName = Constants.ConfigPaths.PROFILE_TYPE_KEY,
                ),
        )

    /**
     * Whether the profile is set up: `profile.yaml` exists, or `settings.yaml` exists and holds
     * credentials. An earlier file that holds none was an interrupted setup, which setup finishes.
     */
    fun isSetup(): Boolean =
        // An earlier file that does not parse counts as set up, so the next load reports why it fails.
        file.exists() ||
            (legacyFile.exists() && (readableLegacyValues()?.let { legacyReader.holdsCredentials(it) } ?: true))

    /**
     * The profile as it is saved now. With only the earlier file, converts it into `profile.yaml`
     * under the lock, prints nothing, and leaves the earlier file as it was.
     *
     * @throws IllegalStateException when the profile is not set up or a file cannot be read
     */
    fun load(): Profile = if (file.exists()) decode() else locked { readUnlocked() }

    /**
     * The saved values as flat setup keys (`region`, `awsProfile`, `keyName`, `sshTransport`, ...),
     * for either file; empty when neither exists. Setup prefills from these, so a value that does
     * not parse, such as an unknown SSH transport, reaches setup to be reported and asked again.
     *
     * @throws ProfileFileException when either file cannot be read or parsed, or `profile.yaml` holds
     *   an unknown key, as [load] does, whatever its values: a misspelled key would read as a blank
     *   value, and setup would create a second bucket or key pair. Setup cannot repair these, so their
     *   remedy is never [ProfileFileRemedy.RUN_SETUP]
     */
    fun loadForSetup(): Map<String, String> =
        when {
            file.exists() -> {
                val node = parse(file)
                failOnUnknownKey(node)
                legacyReader.setupValues(scalars(node, prefix = ""))
            }
            legacyFile.exists() -> legacyValues()
            else -> emptyMap()
        }

    /**
     * Saves [change] applied to the profile as it is saved now, under the lock, and returns the
     * saved profile. A setting another command saved after this one loaded is kept.
     *
     * @throws IllegalStateException when the profile is not set up
     */
    fun update(change: (Profile) -> Profile): Profile = locked { change(readUnlocked()).also { write(it) } }

    /**
     * Saves [profile] whole, under the lock. Only profile setup uses it, to save the profile it
     * collected; every later change is an [update].
     */
    fun replace(profile: Profile) = locked { write(profile) }

    private fun readUnlocked(): Profile =
        when {
            file.exists() -> decode()
            legacyFile.exists() -> migrate()
            else -> error("No profile found in $profileDir. Run 'easy-db-lab profile setup' to create it.")
        }

    private fun migrate(): Profile {
        // Parsed first, so a file that does not parse is reported as one setup cannot repair.
        val values = legacyValues()
        val profile =
            redacted(legacyFile, ProfileFileRemedy.RUN_SETUP) { legacyReader.migrate(values) }
                ?: throw ProfileFileException(
                    legacyFile,
                    "the profile has no credentials",
                    "The profile in $profileDir has no credentials. Run 'easy-db-lab profile setup' to finish it.",
                    cause = null,
                    remedy = ProfileFileRemedy.RUN_SETUP,
                )
        log.debug { "Converted $legacyFile into $file" }
        write(profile)
        return profile
    }

    private fun decode(): Profile {
        val node = parse(file)
        failOnUnknownKey(node)
        // The file parsed and every key is known, so the failure is a value; setup reads raw values and repairs it.
        return redacted(file, ProfileFileRemedy.RUN_SETUP) { yaml.decodeFromYamlNode(Profile.serializer(), node) }
    }

    /**
     * Fails when [node], the parsed `profile.yaml`, holds a key no field declares. No value is looked
     * at, so a value that does not parse is left for [load] to report or for setup to ask again.
     */
    private fun failOnUnknownKey(node: YamlNode) {
        val key = unknownKeys.find(node, Profile.serializer().descriptor) ?: return
        val field = if (key.parentPath.isEmpty()) "" else "field ${key.parentPath}: "
        val reason = "$field${shownKey(key.name)}. Known keys: ${key.knownKeys.sorted().joinToString(", ")}"
        throw unreadable(file, "line ${key.line}, column ${key.column}", reason, cause = null, ProfileFileRemedy.FIX_KEY)
    }

    private fun write(profile: Profile) {
        val text = yaml.encodeToString(Profile.serializer(), profile) + "\n"
        try {
            file.writeTextAtomically(text) { staged ->
                Files.setPosixFilePermissions(staged.toPath(), PosixFilePermissions.fromString(Constants.ConfigPaths.PROFILE_FILE_MODE))
            }
        } catch (e: IOException) {
            throw ProfileFileException(
                file,
                "${e::class.simpleName} while writing",
                "Cannot write the profile file $file: ${e::class.simpleName}",
                e,
                ProfileFileRemedy.FIX_FILE,
            )
        }
    }

    private fun legacyValues(): Map<String, String> = scalars(parse(legacyFile), prefix = "")

    /** The earlier file's values, or null when it cannot be parsed; [load] then reports why. */
    private fun readableLegacyValues(): Map<String, String>? =
        try {
            legacyValues()
        } catch (_: IllegalStateException) {
            null
        }

    /** Setup parses the file too, so a file that cannot be read or parsed is one only the user can fix. */
    private fun parse(source: File): YamlNode = redacted(source, ProfileFileRemedy.FIX_FILE) { yaml.parseToYamlNode(source.readText()) }

    /** Every scalar under [node], keyed by its dotted path. */
    private fun scalars(
        node: YamlNode,
        prefix: String,
    ): Map<String, String> = buildMap { addScalars(node, prefix) }

    private fun MutableMap<String, String>.addScalars(
        node: YamlNode,
        prefix: String,
    ) {
        when (node) {
            is YamlMap -> node.entries.forEach { (key, value) -> addScalars(value, "$prefix${key.content}.") }
            is YamlScalar -> put(prefix.removeSuffix("."), node.content)
            else -> Unit
        }
    }

    /**
     * Runs [read] of [source] and turns a failure into one that carries no text from the file.
     * [remedy] is what fixes a failure of this read: the caller knows whether it parses or decodes.
     */
    private fun <T> redacted(
        source: File,
        remedy: ProfileFileRemedy,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (e: YamlException) {
            throw unreadable(source, "line ${e.line}, column ${e.column}", reason(e), cause = null, remedy)
        } catch (e: SerializationException) {
            throw unreadable(source, null, "a value has the wrong type or form", cause = null, remedy)
        } catch (e: IOException) {
            // A file the user cannot read (permissions, a directory in its place). The message names
            // only the path and the reason, never the file's text.
            throw unreadable(source, null, "the file cannot be read (${e::class.simpleName})", cause = e, remedy)
        } catch (e: IllegalArgumentException) {
            // Only a plain IllegalArgumentException is ours (a failed `require`, such as an unknown SSH
            // transport, which names the bad choice and the valid ones). A subclass such as
            // NumberFormatException comes from the JDK and quotes the bad input.
            val ours = e.takeIf { it.javaClass == IllegalArgumentException::class.java }
            throw unreadable(source, null, ours?.message ?: "a value has the wrong type or form", cause = ours, remedy)
        }

    private fun reason(e: YamlException): String {
        // kaml wraps the precise failure (an unknown type, a failed `require`) in a property error.
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        val ours = chain.firstOrNull { it.javaClass == IllegalArgumentException::class.java }
        val unknownType = chain.filterIsInstance<UnknownPolymorphicTypeException>().firstOrNull()
        val path = e.path.toHumanReadableString()
        val field = path.takeIf { it.isNotEmpty() }?.let { "field $it: " }.orEmpty()
        return field +
            when {
                ours != null -> ours.message.orEmpty()
                unknownType != null ->
                    "unknown type '${unknownType.typeName}'. Known types: ${unknownType.validTypeNames.sorted().joinToString(", ")}"
                e is MissingRequiredPropertyException -> "'${e.propertyName}' is required but missing"
                e is MalformedYamlException -> "the file is not valid YAML"
                else -> "a value has the wrong type or form"
            }
    }

    /** The key's name when it is a short identifier, else no name: a key is file text and could be a secret. */
    private fun shownKey(name: String): String = if (shownKeyPattern.matches(name)) "unknown key '$name'" else "an unknown key"

    private fun unreadable(
        source: File,
        where: String?,
        reason: String,
        cause: Throwable?,
        remedy: ProfileFileRemedy,
    ): ProfileFileException =
        ProfileFileException(
            source,
            where?.let { "$it: $reason" } ?: reason,
            "Cannot read $source${where?.let { " at $it" }.orEmpty()}: $reason. " +
                "The text of the file is not shown because it can hold credentials. " +
                when (remedy) {
                    ProfileFileRemedy.RUN_SETUP -> "Fix the file or run 'easy-db-lab profile setup'."
                    ProfileFileRemedy.FIX_KEY -> "Fix or remove that key in the file; 'easy-db-lab profile setup' would stop at it too."
                    ProfileFileRemedy.FIX_FILE -> "Fix the file; 'easy-db-lab profile setup' would stop at it too."
                },
            cause,
            remedy,
        )

    /** Runs [block] while this thread holds [localLock] and this process holds the lock file. */
    private fun <T> locked(block: () -> T): T =
        localLock.withLock {
            // A second file lock from this thread would throw OverlappingFileLockException, which names no profile.
            check(localLock.holdCount == 1) {
                "A profile change saved the profile again from inside the change. Save once, after the change returns."
            }
            profileDir.mkdirs()
            val channel = onLockFile("open") { FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE) }
            channel.use { onLockFile("lock") { it.lock() }.use { block() } }
        }

    /** Runs [action] on the lock file and reports an I/O failure as a [ProfileFileException] that names it. */
    private fun <T> onLockFile(
        verb: String,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (e: IOException) {
            throw ProfileFileException(
                lockFile,
                "${e::class.simpleName} while ${verb}ing the lock file",
                "Cannot $verb the profile lock file $lockFile: ${e::class.simpleName}",
                e,
                ProfileFileRemedy.FIX_FILE,
            )
        }

    private companion object {
        val log = KotlinLogging.logger {}

        val shownKeyPattern = Regex(Constants.ConfigPaths.PROFILE_SHOWN_KEY_PATTERN)

        /** Taken before the file lock, by every thread of this JVM, for every profile. */
        val localLock = ReentrantLock()
    }
}
