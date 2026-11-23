package com.rustyrazorblade.easydblab.containers

import com.github.dockerjava.api.model.AccessMode
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.Docker
import com.rustyrazorblade.easydblab.VolumeMapping
import com.rustyrazorblade.easydblab.commands.mixins.BuildArgsMixin
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestinationAccess
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import com.rustyrazorblade.easydblab.providers.ssh.RegionalRemoteOperations
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.runCatchingInterruptibly
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.FileUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess

/**
 * Builds one AMI from the Packer templates in [directory], running Packer in a container.
 *
 * Each build has its own id, carried on everything it launches, and runs on a [BuildSite] in its
 * own region. A build that loses its SSH session to the builder runs again on a new builder (see
 * [PackerBuildAttempts]); a failed build's builders are torn down unless kept, and only this
 * build's builders are ever touched.
 */
class Packer(
    val context: Context,
    var directory: String,
) : KoinComponent {
    // Builds or pulls the Packer image; each build attempt then runs in a Docker of its own (execute).
    private val imageDocker: Docker by inject { parametersOf(context) }
    private val packerImage: PackerImage by inject { parametersOf(imageDocker) }
    private val eventBus: EventBus by inject()
    private val awsDestination: AwsDestinationAccess by inject()
    private val buildSites: BuildSites by inject()

    // Used on the last failed attempt to capture diagnostics from its builders, in the build region.
    private val regionalRemote: RegionalRemoteOperations by inject()

    private val awsCredentialsManager: AWSCredentialsManager by inject()
    private val secretKeyFile by lazy { File(context.profileDir, Constants.ConfigPaths.PROFILE_SSH_KEY_FILE) }

    private val containerWorkingDir = Constants.Paths.LOCAL_MOUNT
    private val logger = KotlinLogging.logger {}

    companion object {
        /**
         * The `packer build` arguments for one AMI build.
         *
         * Under the `ssm` SSH transport Packer reaches the builder through Session Manager instead of
         * the instance's public IP, so a network that blocks outbound port 22 can still build AMIs.
         * Every build bounds its SSH stream with a keepalive and a read/write timeout, and tags what
         * it launches with the build's id besides the marker tags.
         *
         * @param keyPairName the key pair in the build region the builder launches with; its private key is the profile's
         * @param releaseVersion the version to stamp a release build with, or null for a timestamped build
         */
        internal fun buildCommand(
            build: PackerBuild,
            arch: Arch,
            destination: AwsDestination,
            keyPairName: String,
            releaseVersion: String?,
        ): List<String> =
            buildList {
                add("build")
                // Always keep the instance on failure so we can capture diagnostics from it
                // before deciding whether to tear it down (see cleanUp).
                add("-on-error=abort")
                addVar("region", build.region)
                addVar("arch", arch.type)
                addVar("s3_bucket", destination.accountBucket)
                // Build with a key pair holding the user's own key so an instance left up via
                // --keep-on-error can be SSH'd into with ${profileDir}/secret.pem.
                addVar("ssh_keypair_name", keyPairName)
                addVar("ssh_private_key_file", Constants.Paths.SSH_KEY_MOUNT)
                if (destination.sshTransport == SshTransport.Ssm) {
                    addVar("ssh_interface", Constants.Packer.SESSION_MANAGER_INTERFACE)
                }
                // Keepalive replies keep a quiet script's stream busy, and the read/write timeout
                // ends a stream that carries nothing, so a dead session fails instead of hanging.
                addVar("ssh_keep_alive_interval", Constants.Packer.SSH_KEEP_ALIVE_INTERVAL)
                addVar("ssh_read_write_timeout", Constants.Packer.SSH_READ_WRITE_TIMEOUT)
                releaseVersion?.let { addVar("release_version", it) }
                addVar("marker_tags", hclMap(Constants.Vpc.MARKER_TAGS + (Constants.Packer.BUILD_ID_TAG_KEY to build.buildId)))
                add(build.template)
            }

        /**
         * Runs [block] on [build]'s site in its region, with remote operations that reach that
         * region's hosts, and closes both when it returns.
         */
        internal fun <T> inBuildRegion(
            build: PackerBuild,
            sites: BuildSites,
            remotes: RegionalRemoteOperations,
            block: (BuildSite, RemoteOperationsService) -> T,
        ): T =
            sites.open(build.region, build.buildId).use { site ->
                remotes.forRegion(site.region).use { remote -> block(site, remote.operations) }
            }

        /** A new build id: enough random hex that concurrent builds never share one. */
        internal fun newBuildId(): String =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(Constants.Packer.BUILD_ID_LENGTH)

        /**
         * An HCL map literal for a `-var` of type `map(string)`, e.g. `{"a" = "1", "b" = "2"}`.
         * A backslash or a double quote in a key or value is escaped, so it stays inside its string.
         */
        internal fun hclMap(entries: Map<String, String>): String =
            entries.entries.joinToString(prefix = "{", postfix = "}", separator = ", ") { (key, value) ->
                "${hclString(key)} = ${hclString(value)}"
            }

        /** [text] as a quoted HCL string. */
        private fun hclString(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun MutableList<String>.addVar(
            key: String,
            value: String,
        ) {
            add("-var")
            add("$key=$value")
        }
    }

    /**
     * The `packer build` arguments for [build] with the profile as it is saved now, so a bucket that
     * setup created after this Packer was made is the one the build uses.
     */
    internal fun buildCommand(
        build: PackerBuild,
        arch: Arch,
        keyPairName: String,
        releaseVersion: String?,
    ): List<String> = buildCommand(build, arch, awsDestination.current(), keyPairName, releaseVersion)

    fun build(
        name: String,
        buildArgs: BuildArgsMixin,
    ) {
        buildInternal(name, buildArgs.region, buildArgs.arch, buildArgs.release, buildArgs.keepOnError)
    }

    private fun buildInternal(
        name: String,
        region: String,
        arch: Arch,
        isRelease: Boolean,
        keepOnError: Boolean,
    ) {
        require(name.isNotBlank()) { "Build name cannot be blank" }
        require(region.isNotBlank()) { "Build region cannot be blank" }
        // Read when the build starts, not when this Packer was made: setup creates the key pair and
        // bucket, then builds the image in the same process.
        val destination = awsDestination.current()
        require(destination.keyName.isNotEmpty() && secretKeyFile.exists()) {
            "AWS keypair not configured (key: '${destination.keyName}', file: ${secretKeyFile.absolutePath}). Run profile setup first."
        }

        val build =
            PackerBuild(
                template = name,
                buildId = newBuildId(),
                region = region,
                maxAttempts = Constants.Packer.MAX_BUILD_ATTEMPTS,
                keepOnError = keepOnError || keepOnErrorFromEnv(),
            )
        val packerDir = packerDirectory()
        // Resolved first, so a failed image build stops before Packer launches any instance.
        val image = packerImage.ensure(destination.sshTransport)
        val releaseVersion = if (isRelease) context.version.toString() else null
        val outcome =
            inBuildRegion(build, buildSites, regionalRemote) { site, remote ->
                // A release build uses the release version as the image version and makes the AMI public.
                val command = buildCommand(build, arch, site.keyPairName, releaseVersion)
                runAttempts(build, site, remote) { _, logFile -> runAttempt(image, command, packerDir, logFile) }
            }
        when (outcome) {
            is PackerBuildOutcome.Succeeded -> logger.info { "Packer build succeeded" }
            is PackerBuildOutcome.Failed -> exitProcess(1)
        }
    }

    /**
     * Runs the attempts of [build] on [site], each through [attemptRun] with its own log file, and
     * reaches a failed attempt's builders through [remote], which reaches the build region.
     */
    private fun runAttempts(
        build: PackerBuild,
        site: BuildSite,
        remote: RemoteOperationsService,
        attemptRun: (attempt: Int, logFile: File) -> PackerBuildOutcome,
    ): PackerBuildOutcome {
        println("AMI build ${build.template}: build id ${build.buildId}, region ${build.region}.")
        val logs = PackerLogs(File(context.profileDir, Constants.Packer.HOST_LOGS_DIR))
        return PackerBuildAttempts(build.keepOnError, build.maxAttempts).run(
            build = { attempt -> attemptRun(attempt, logs.newAttempt(build.template, build.buildId, attempt)) },
            cleanUp = { attempt, failed, plan ->
                // The header comes first, so the cleanup's own lines read as part of this attempt.
                println(attemptHeader(build, attempt, failed, plan))
                cleanUp(site, remote, plan)
            },
            report = { attempt, failed, willRetry, cleanup ->
                eventBus.emit(PackerAttempt.failedEvent(build, attempt, failed, willRetry, cleanup))
            },
        )
    }

    private fun attemptHeader(
        build: PackerBuild,
        attempt: Int,
        failed: PackerBuildOutcome.Failed,
        plan: CleanupPlan,
    ): String {
        val why =
            when (failed.failure) {
                is PackerFailure.SessionDropped -> "lost its SSH session to the build instance"
                is PackerFailure.BuildError -> "failed"
            }
        val next =
            when {
                plan.inspect -> "Capturing diagnostics from its build instance."
                else -> "Tearing down its build instance before the next attempt."
            }
        return "==== AMI build ${build.template} attempt $attempt of ${build.maxAttempts} $why. $next"
    }

    /**
     * Runs one `packer build` in a new container, with its debug log at [logFile], and classifies the
     * result ([PackerAttempt.outcome]). An interrupt is a request to stop, so it propagates.
     */
    private fun runAttempt(
        image: String,
        command: List<String>,
        packerDir: File,
        logFile: File,
    ): PackerBuildOutcome {
        val run = runCatchingInterruptibly { execute(image, command, packerDir, logFile) }
        run.onFailure { logger.error(it) { "Packer container failed" } }
        return PackerAttempt.outcome(run, logFile)
    }

    /**
     * Whether the EASY_DB_LAB_BUILD_KEEP_ON_FAILURE env var is set to a truthy value. Lets builds
     * triggered indirectly (e.g. by profile setup) keep the instance up on failure without a flag.
     */
    private fun keepOnErrorFromEnv(): Boolean =
        System.getenv(Constants.Environment.BUILD_KEEP_ON_FAILURE)?.lowercase() in setOf("1", "true", "yes", "on")

    /**
     * After a failed attempt, captures diagnostics from its builders when the [plan] asks (packer ran
     * with -on-error=abort), and keeps or terminates them through [BuilderCleaner], whose answer
     * decides whether the build may run again. A kept builder gets its SSH lines here; the
     * attempt's event reports every outcome.
     */
    private fun cleanUp(
        site: BuildSite,
        remote: RemoteOperationsService,
        plan: CleanupPlan,
    ): BuilderCleanup {
        var found: List<BuilderInstance> = emptyList()
        val cleanup =
            BuilderCleaner(site.builders).cleanUp(
                plan,
                inspect = { builders ->
                    found = builders
                    builders.forEach { builder ->
                        builder.publicIp?.takeIf { it.isNotBlank() }?.let { captureAndPrintDiagnostics(remote, it, builder.id) }
                    }
                },
                inspectFailed = { reason -> println("Could not capture diagnostics from the build instance: $reason") },
            )
        if (cleanup is BuilderCleanup.Kept) {
            val ips = found.associate { it.id to it.publicIp }
            println(
                cleanup.instanceIds.joinToString("\n") { id ->
                    "SSH in:  ssh -i ${secretKeyFile.absolutePath} ubuntu@${ips[id]?.takeIf { it.isNotBlank() } ?: "<instance-ip>"}"
                },
            )
        }
        return cleanup
    }

    /**
     * SSHes into the kept build instance and prints the most useful failure diagnostics.
     * Best-effort: if the instance is unreachable, prints manual SSH instructions instead.
     */
    private fun captureAndPrintDiagnostics(
        remote: RemoteOperationsService,
        ip: String,
        instanceId: String,
    ) {
        val cmd =
            "echo '--- df / ---'; df -h /; " +
                "echo '--- /tmp/jdk-install.log (tail) ---'; tail -60 /tmp/jdk-install.log 2>/dev/null; " +
                "echo '--- /var/log/apt/term.log (tail) ---'; sudo tail -100 /var/log/apt/term.log 2>/dev/null; " +
                "echo '--- dmesg (tail) ---'; sudo dmesg 2>/dev/null | tail -40"
        runCatchingInterruptibly {
            val host = Host(public = ip, private = "", alias = "packer-build", availabilityZone = "", instanceId = instanceId)
            val response = remote.executeRemotely(host, cmd, output = false)
            println(
                """
                |==== build instance diagnostics ($ip) ====
                |${response.text}
                |==========================================
                """.trimMargin(),
            )
        }.onFailure {
            println(
                """
                |Could not auto-capture diagnostics from $ip (${it.messageOrString()}).
                |SSH in manually: ssh -i ${secretKeyFile.absolutePath} ubuntu@$ip
                """.trimMargin(),
            )
        }
    }

    /** A copy of this build's packer directory, which every attempt mounts read-only. */
    private fun packerDirectory(): File {
        require(directory.isNotBlank()) { "Directory cannot be blank" }
        val localPackerPath = context.packerHome + directory
        if (!File(localPackerPath).exists()) {
            eventBus.emit(Event.Docker.PackerDirectoryNotFound(localPackerPath))
            exitProcess(1)
        }
        val tempDir = createTempDirectory().toFile()
        FileUtils.copyDirectory(File(localPackerPath), tempDir)
        logger.info { "Copied packer files from $localPackerPath to $tempDir" }
        return tempDir
    }

    /**
     * Runs [command] in a new Packer container, so no attempt inherits another's mounts, with
     * Packer's debug log written to [logFile] on the host.
     */
    private fun execute(
        image: String,
        command: List<String>,
        packerDir: File,
        logFile: File,
    ): Result<String> {
        require(command.isNotEmpty()) { "Commands cannot be empty" }
        logger.info { "Mounting $packerDir to $containerWorkingDir, starting with $command" }

        val creds = Constants.Paths.CREDENTIALS_MOUNT
        val containerLog = "${Constants.Packer.CONTAINER_LOG_DIR}/${logFile.name}"
        val docker: Docker = get { parametersOf(context) }

        return docker
            .addVolume(VolumeMapping(packerDir.absolutePath, containerWorkingDir, AccessMode.ro))
            .addVolume(VolumeMapping(awsCredentialsManager.credentialsPath, creds, AccessMode.ro))
            // Mount the user's private key so packer authenticates with their keypair
            .addVolume(VolumeMapping(secretKeyFile.absolutePath, Constants.Paths.SSH_KEY_MOUNT, AccessMode.ro))
            .addVolume(VolumeMapping(logFile.parentFile.absolutePath, Constants.Packer.CONTAINER_LOG_DIR, AccessMode.rw))
            .addEnv("${Constants.AWS.SHARED_CREDENTIALS_FILE_ENV}=$creds")
            // Setting the path alone turns Packer's debug log on.
            .addEnv("${Constants.Packer.LOG_PATH_ENV}=$containerLog")
            .runContainer(
                image,
                command.toMutableList(),
                containerWorkingDir,
                Duration.ofMinutes(Constants.Packer.BUILD_TIMEOUT_MINUTES),
            )
    }
}
