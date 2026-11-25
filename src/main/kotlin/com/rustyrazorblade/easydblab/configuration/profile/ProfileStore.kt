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
 * and a change must not call back into the store.
 *
 * The earlier file is read, never written, moved or deleted, so a build of an earlier release keeps
 * working on the same profile. The store names no destination type: the [serializers] register each
 * destination, and [legacyReader] knows the earlier file's keys.
 *
 * Parse errors never carry text from the file, which can hold an AWS secret, the AxonOps key or the
 * Tailscale client secret. They name the file, where parsing stopped and what kind of problem it was.
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

    private val yaml =
        Yaml(
            serializersModule = serializers,
            configuration =
                YamlConfiguration(
                    strictMode = false,
                    polymorphismStyle = PolymorphismStyle.Property,
                    polymorphismPropertyName = Constants.ConfigPaths.PROFILE_TYPE_KEY,
                ),
        )

    /**
     * Whether the profile is set up: `profile.yaml` exists, or `settings.yaml` exists and holds
     * credentials. An earlier file that holds none was an interrupted setup, which setup finishes.
     */
    fun isSetup(): Boolean =
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
     */
    fun loadForSetup(): Map<String, String> =
        when {
            file.exists() -> legacyReader.setupValues(scalars(parse(file), prefix = ""))
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
        val profile =
            redacted(legacyFile) { legacyReader.migrate(legacyValues()) }
                ?: throw ProfileUnreadableException(
                    legacyFile,
                    "The profile in $profileDir has no credentials. Run 'easy-db-lab profile setup' to finish it.",
                    cause = null,
                )
        log.debug { "Converted $legacyFile into $file" }
        write(profile)
        return profile
    }

    private fun decode(): Profile = redacted(file) { yaml.decodeFromString(Profile.serializer(), file.readText()) }

    private fun write(profile: Profile) {
        val text = yaml.encodeToString(Profile.serializer(), profile) + "\n"
        try {
            file.writeTextAtomically(text) { staged ->
                Files.setPosixFilePermissions(staged.toPath(), PosixFilePermissions.fromString(Constants.ConfigPaths.PROFILE_FILE_MODE))
            }
        } catch (e: IOException) {
            throw ProfileUnreadableException(file, "Cannot write the profile file $file: ${e::class.simpleName}", e)
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

    private fun parse(source: File): YamlNode = redacted(source) { yaml.parseToYamlNode(source.readText()) }

    /** Every scalar under [node], keyed by its dotted path. */
    private fun scalars(
        node: YamlNode,
        prefix: String,
    ): Map<String, String> =
        when (node) {
            is YamlMap ->
                node.entries.entries.fold(emptyMap()) { found, (key, value) -> found + scalars(value, "$prefix${key.content}.") }
            is YamlScalar -> mapOf(prefix.removeSuffix(".") to node.content)
            else -> emptyMap()
        }

    /** Runs [read] of [source] and turns a failure into one that carries no text from the file. */
    private fun <T> redacted(
        source: File,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (e: YamlException) {
            throw unreadable(source, "line ${e.line}, column ${e.column}", reason(e), cause = null)
        } catch (e: SerializationException) {
            throw unreadable(source, null, "a value has the wrong type or form", cause = null)
        } catch (e: IOException) {
            // A file the user cannot read (permissions, a directory in its place). The message names
            // only the path and the reason, never the file's text.
            throw unreadable(source, null, "the file cannot be read (${e::class.simpleName})", cause = e)
        } catch (e: IllegalArgumentException) {
            // Only a plain IllegalArgumentException is ours (a failed `require`, such as an unknown SSH
            // transport, which names the bad choice and the valid ones). A subclass such as
            // NumberFormatException comes from the JDK and quotes the bad input.
            val ours = e.takeIf { it.javaClass == IllegalArgumentException::class.java }
            throw unreadable(source, null, ours?.message ?: "a value has the wrong type or form", cause = ours)
        }

    private fun reason(e: YamlException): String {
        val field =
            e.path
                .toHumanReadableString()
                .takeIf { it.isNotEmpty() }
                ?.let { "field $it: " }
                .orEmpty()
        // kaml wraps the precise failure (an unknown type, a failed `require`) in a property error.
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        val ours = chain.firstOrNull { it.javaClass == IllegalArgumentException::class.java }
        val unknownType = chain.filterIsInstance<UnknownPolymorphicTypeException>().firstOrNull()
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

    private fun unreadable(
        source: File,
        where: String?,
        reason: String,
        cause: Throwable?,
    ): ProfileUnreadableException =
        ProfileUnreadableException(
            source,
            "Cannot read $source${where?.let { " at $it" }.orEmpty()}: $reason. " +
                "The text of the file is not shown because it can hold credentials. " +
                "Fix the file or run 'easy-db-lab profile setup'.",
            cause,
        )

    /** Runs [block] while this thread holds [localLock] and this process holds the lock file. */
    private fun <T> locked(block: () -> T): T =
        localLock.withLock {
            profileDir.mkdirs()
            val channel =
                try {
                    FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                } catch (e: IOException) {
                    throw ProfileUnreadableException(lockFile, "Cannot open the profile lock file $lockFile: ${e::class.simpleName}", e)
                }
            channel.use {
                val held =
                    try {
                        it.lock()
                    } catch (e: IOException) {
                        throw ProfileUnreadableException(lockFile, "Cannot lock the profile lock file $lockFile: ${e::class.simpleName}", e)
                    }
                held.use { block() }
            }
        }

    private companion object {
        val log = KotlinLogging.logger {}

        /** Taken before the file lock, by every thread of this JVM, for every profile. */
        val localLock = ReentrantLock()
    }
}
