package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.CassandraBuildManifest
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/**
 * Builds a Cassandra branch checkout on this machine and describes what came out.
 *
 * This is the committer's inner loop: point at the tree you are already working in, get a tarball
 * plus a manifest that identifies it. It deliberately builds in place with the ant you already
 * have rather than in a container — the tree is warm, and reproducibility is the CI pipeline's
 * job, not this one's.
 *
 * A dirty tree is allowed and recorded, never rejected. Refusing to build one would make the
 * command useless for the case it exists to serve.
 */
class CassandraBuildService(
    private val eventBus: EventBus,
) {
    private val log = KotlinLogging.logger {}

    /**
     * What to build.
     *
     * @property sourceDir a Cassandra git checkout
     * @property javaVersion JDK major version to build under; part of the resulting name
     * @property jira the ticket this build is for; never inferred, a branch name is not evidence
     * @property label free-form `--name` text folded into the build's name
     * @property today injected so naming is testable
     */
    data class Request(
        val sourceDir: File,
        val javaVersion: String,
        val jira: String? = null,
        val label: String? = null,
        val antFlags: String? = null,
        val builtBy: String,
        val today: LocalDate = LocalDate.now(),
    )

    /** The tarball and the manifest describing it, both still local. */
    data class Result(
        val manifest: CassandraBuildManifest,
        val tarball: File,
    )

    /** Git facts about the tree at the moment the build started. */
    internal data class GitState(
        val sha: String,
        val shortSha: String,
        val branch: String,
        val remote: String?,
        val dirty: Boolean,
    )

    fun build(request: Request): Result {
        val sourceDir = request.sourceDir.absoluteFile
        val buildXml = validateCheckout(sourceDir)

        val baseVersion = readBaseVersion(buildXml.readText(), buildXml.path)
        val git = gitState(sourceDir)
        val jira = request.jira?.uppercase()?.ifBlank { null }
        val label = request.label?.let { CassandraBuildManifest.validateLabel(it) }
        val name =
            CassandraBuildManifest.buildName(
                baseVersion = baseVersion,
                jira = jira,
                date = request.today,
                shortSha = git.shortSha,
                javaVersion = request.javaVersion,
                label = label,
            )
        val javaHome = resolveJavaHome(request.javaVersion)
        val startedAt = Instant.now()

        eventBus.emit(
            Event.Cassandra.BuildStarting(
                name = name,
                sourceDir = sourceDir.path,
                baseVersion = baseVersion,
                branch = git.branch,
                shortSha = git.shortSha,
                dirty = git.dirty,
                javaVersion = request.javaVersion,
                javaHome = javaHome.path,
            ),
        )

        runAnt(sourceDir, javaHome, listOf("realclean"))
        runAnt(sourceDir, javaHome, listOf("artifacts", "-Dcheck.skip=true", "-Dant.gen-doc.skip=true") + antFlagList(request.antFlags))

        val tarball = locateTarball(File(sourceDir, BUILD_DIR))
        val manifest =
            CassandraBuildManifest(
                name = name,
                baseVersion = CassandraBuildManifest.stripSnapshot(baseVersion),
                javaVersion = request.javaVersion,
                gitSha = git.sha,
                gitShortSha = git.shortSha,
                gitBranch = git.branch,
                gitRemote = git.remote,
                dirty = git.dirty,
                jira = jira,
                label = label,
                antFlags = request.antFlags?.ifBlank { null },
                builtAt = startedAt.toString(),
                builtBy = request.builtBy,
                tarball = CassandraBuildManifest.tarballName(name),
                tarballBytes = tarball.length(),
                tarballSha256 = sha256(tarball),
            )

        eventBus.emit(Event.Cassandra.BuildArtifactReady(name, tarball.length()))
        return Result(manifest, tarball)
    }

    /**
     * Checks the directory is something this can build, and returns its build.xml.
     *
     * Every check runs before any work starts, so pointing at the wrong directory costs a message
     * rather than a clean and a failed compile.
     */
    internal fun validateCheckout(sourceDir: File): File {
        val buildXml = File(sourceDir, BUILD_XML)
        require(sourceDir.isDirectory) { "Not a directory: $sourceDir" }
        require(buildXml.isFile) {
            "$sourceDir is not a Cassandra checkout: no $BUILD_XML. Point at the root of a Cassandra source tree."
        }
        require(File(sourceDir, ".git").exists()) {
            "$sourceDir is not a git checkout, so there is no sha to name the build after."
        }
        return buildXml
    }

    /**
     * Runs one ant target, streaming output so a build that stalls is visibly stalled.
     *
     * JAVA_HOME is set for the child only. Changing the caller's JDK would be a side effect well
     * outside what a build command should reach for, and PATH is left alone so the ant on the
     * user's PATH is the ant that runs.
     */
    private fun runAnt(
        sourceDir: File,
        javaHome: File,
        args: List<String>,
    ) {
        val command = listOf(ANT) + args
        log.info { "Running ${command.joinToString(" ")} in $sourceDir with JAVA_HOME=$javaHome" }

        val process =
            ProcessBuilder(command)
                .directory(sourceDir)
                .redirectErrorStream(true)
                .also { it.environment()["JAVA_HOME"] = javaHome.path }
                .start()

        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { println(it) } }

        val exit = process.waitFor()
        check(exit == 0) { "${command.joinToString(" ")} failed with exit code $exit in $sourceDir" }
    }

    private fun gitState(sourceDir: File): GitState {
        val sha = git(sourceDir, "rev-parse", "HEAD")
        return GitState(
            sha = sha,
            shortSha = sha.take(SHORT_SHA_LENGTH),
            branch = git(sourceDir, "rev-parse", "--abbrev-ref", "HEAD"),
            remote = runCatching { git(sourceDir, "config", "--get", "remote.origin.url") }.getOrNull()?.ifBlank { null },
            // --porcelain lists untracked files too, which is what "dirty" has to mean here: a new
            // unstaged source file is compiled into the artifact exactly like a modified one.
            dirty = git(sourceDir, "status", "--porcelain").isNotBlank(),
        )
    }

    private fun git(
        sourceDir: File,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf(GIT) + args)
                .directory(sourceDir)
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed in $sourceDir: $output" }
        return output
    }

    /**
     * The JDK to build under, searched across the layouts a developer machine actually uses.
     *
     * Fails naming every location it looked in: "JDK 17 not found" with no list is a dead end for
     * whoever hits it.
     */
    internal fun resolveJavaHome(javaVersion: String): File {
        // The JDK the operator has already selected wins when it is the one they asked for. A
        // SDKMAN or jenv user means "the 21 I am using", not whichever 21 sorts first on disk.
        selectedJavaHome()?.takeIf { javaMajorOf(it) == javaVersion }?.let { return it }

        macOsJavaHome(javaVersion)?.let { return it }

        val candidates = javaHomeCandidates(javaVersion)
        candidates.firstOrNull { File(it, "bin/javac").canExecute() }?.let { return it }

        error(
            "No JDK $javaVersion found. Looked at JAVA_HOME, /usr/libexec/java_home -v $javaVersion, and:\n" +
                candidates.joinToString("\n") { "  $it" },
        )
    }

    /** Whatever JDK the shell is currently pointed at, if any. */
    private fun selectedJavaHome(): File? =
        System
            .getenv("JAVA_HOME")
            ?.ifBlank { null }
            ?.let(::File)
            ?.takeIf { it.isDirectory }

    /**
     * The major version a JDK install actually is, read from its own `release` file rather than
     * guessed from a directory name — `current` and other symlinks carry no version in their name.
     */
    internal fun javaMajorOf(javaHome: File): String? {
        val version = javaVersionOf(javaHome) ?: return null
        // 1.8.0_452 is Java 8; everything since is majored on the leading number.
        return if (version.startsWith("1.")) version.split(".").getOrNull(1) else version.substringBefore(".")
    }

    /** The `JAVA_VERSION` a JDK install declares, or null when this is not a JDK. */
    private fun javaVersionOf(javaHome: File): String? =
        File(javaHome, "release")
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith(JAVA_VERSION_KEY) }
            ?.substringAfter('=')
            ?.trim('"', ' ')
            ?.ifBlank { null }

    /** macOS keeps its JDKs behind a helper rather than a predictable path. */
    private fun macOsJavaHome(javaVersion: String): File? {
        val helper = File(MAC_JAVA_HOME_TOOL)
        if (!helper.canExecute()) return null
        return runCatching {
            val process = ProcessBuilder(MAC_JAVA_HOME_TOOL, "-v", javaVersion).start()
            val path =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            if (process.waitFor() == 0 && path.isNotBlank()) File(path) else null
        }.getOrNull()
    }

    /**
     * Every JDK install found on this machine that reports the requested major, newest first.
     *
     * Candidates are identified by what each install says about itself in its own `release` file,
     * never by its directory name. A layout like `21.0.10-amzn` is SDKMAN's convention, not a
     * contract, and reading a version out of it breaks the moment a distribution names things
     * differently.
     */
    private fun javaHomeCandidates(javaVersion: String): List<File> {
        val sdkmanRoot =
            File(
                System.getenv("SDKMAN_DIR") ?: File(System.getProperty("user.home"), ".sdkman").path,
                "candidates/java",
            )

        return listOf(sdkmanRoot, File(LINUX_JVM_DIR))
            .flatMap { root -> root.listFiles()?.toList() ?: emptyList() }
            .filter { it.isDirectory && javaMajorOf(it) == javaVersion }
            .sortedByDescending { javaVersionKey(it) }
    }

    /**
     * A JDK's full version as a single comparable number, so 21.0.10 sorts above 21.0.8 — which
     * comparing the strings does not.
     */
    private fun javaVersionKey(javaHome: File): Long =
        javaVersionOf(javaHome)
            ?.split('.', '_', '-')
            ?.mapNotNull { it.toIntOrNull() }
            .orEmpty()
            .let { parts -> List(VERSION_PARTS) { parts.getOrElse(it) { 0 } } }
            .fold(0L) { acc, part -> acc * VERSION_RADIX + part }

    /**
     * The binary tarball `ant artifacts` produced.
     *
     * Its name carries the tree's own version, not the build name, so it is found by shape and
     * renamed on upload.
     */
    internal fun locateTarball(buildDir: File): File {
        val matches =
            (buildDir.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && it.name.startsWith(TARBALL_PREFIX) && it.name.endsWith(TARBALL_SUFFIX) }

        check(matches.isNotEmpty()) {
            "ant artifacts produced no $TARBALL_PREFIX*$TARBALL_SUFFIX in $buildDir"
        }
        check(matches.size == 1) {
            "Expected one binary tarball in $buildDir, found ${matches.size}: " +
                matches.joinToString(", ") { it.name } +
                ". Run 'ant realclean' in the source tree and try again."
        }
        return matches.single()
    }

    companion object {
        private const val BUILD_XML = "build.xml"
        private const val BUILD_DIR = "build"
        private const val ANT = "ant"
        private const val GIT = "git"
        private const val SHORT_SHA_LENGTH = 7
        private const val TARBALL_PREFIX = "apache-cassandra-"
        private const val TARBALL_SUFFIX = "-bin.tar.gz"
        private const val MAC_JAVA_HOME_TOOL = "/usr/libexec/java_home"
        private const val LINUX_JVM_DIR = "/usr/lib/jvm"
        private const val JAVA_VERSION_KEY = "JAVA_VERSION="
        private const val SHA256_BUFFER_BYTES = 8192

        /** Version components compared when ordering JDKs (major, minor, patch, build). */
        private const val VERSION_PARTS = 4

        /** Wide enough that a patch number never carries into the next component. */
        private const val VERSION_RADIX = 100_000L

        private val BASE_VERSION_PATTERN =
            Regex("""property\s+name="base\.version"\s+value="([^"]+)"""")

        /**
         * The version the branch declares for itself.
         *
         * build.xml is authoritative here; deriving a version from the branch name guesses at
         * something the tree already states exactly.
         */
        internal fun readBaseVersion(
            buildXml: String,
            path: String,
        ): String =
            BASE_VERSION_PATTERN
                .find(buildXml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.ifBlank { null }
                ?: error("Could not read the base.version property from $path")

        internal fun antFlagList(antFlags: String?): List<String> =
            antFlags?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(SHA256_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
