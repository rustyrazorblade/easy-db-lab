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
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.runCatchingException
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.FileUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import java.io.File
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess

/**
 * Builds one AMI from the Packer templates in [directory], running Packer in a container.
 *
 * A build that loses its SSH session to the builder runs again on a new builder (see
 * [PackerBuildAttempts]); a failed build's builder is diagnosed and torn down unless kept.
 */
class Packer(
    val context: Context,
    var directory: String,
) : KoinComponent {
    // Builds or pulls the Packer image; each build attempt then runs in a Docker of its own (execute).
    private val imageDocker: Docker by inject { parametersOf(context) }
    private val packerImage: PackerImage by inject { parametersOf(imageDocker) }
    private val eventBus: EventBus by inject()
    private val user: User by inject()

    // Used on build failure to capture diagnostics from, and tear down, the kept instance.
    private val vpcService: VpcService by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val remoteOps: RemoteOperationsService by inject()

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
         * Every build bounds its SSH stream with a keepalive and a read/write timeout.
         *
         * @param releaseVersion the version to stamp a release build with, or null for a timestamped build
         */
        internal fun buildCommand(
            name: String,
            region: String,
            arch: Arch,
            user: User,
            releaseVersion: String?,
        ): List<String> =
            buildList {
                add("build")
                // Always keep the instance on failure so we can capture diagnostics from it
                // before deciding whether to tear it down (see handleBuildFailure).
                add("-on-error=abort")
                addVar("region", region)
                addVar("arch", arch.type)
                addVar("s3_bucket", user.s3Bucket)
                // Build with the user's own AWS keypair so an instance left up via --keep-on-error
                // can be SSH'd into with ${profileDir}/secret.pem.
                addVar("ssh_keypair_name", user.keyName)
                addVar("ssh_private_key_file", Constants.Paths.SSH_KEY_MOUNT)
                if (user.sshTransport == SshTransport.Ssm) {
                    addVar("ssh_interface", Constants.Packer.SESSION_MANAGER_INTERFACE)
                }
                // Keepalive replies keep a quiet script's stream busy, and the read/write timeout
                // ends a stream that carries nothing, so a dead session fails instead of hanging.
                addVar("ssh_keep_alive_interval", Constants.Packer.SSH_KEEP_ALIVE_INTERVAL)
                addVar("ssh_read_write_timeout", Constants.Packer.SSH_READ_WRITE_TIMEOUT)
                releaseVersion?.let { addVar("release_version", it) }
                addVar("marker_tags", hclMap(Constants.Vpc.MARKER_TAGS))
                add(name)
            }

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

    // todo include the region defined in the profile
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

        val keepUp = keepOnError || keepOnErrorFromEnv()

        require(user.keyName.isNotEmpty() && secretKeyFile.exists()) {
            "AWS keypair not configured (key: '${user.keyName}', file: ${secretKeyFile.absolutePath}). Run profile setup first."
        }

        // A release build uses the release version as the image version and makes the AMI public.
        val command =
            buildCommand(
                name = name,
                region = region,
                arch = arch,
                user = user,
                releaseVersion = if (isRelease) context.version.toString() else null,
            )

        val packerDir = packerDirectory()
        // Resolved first, so a failed image build stops before Packer launches any instance.
        val image = packerImage.ensure(user.sshTransport)
        val logs = PackerLogs(File(context.profileDir, Constants.Packer.HOST_LOGS_DIR))
        val maxAttempts = Constants.Packer.MAX_BUILD_ATTEMPTS
        val outcome =
            PackerBuildAttempts(maxAttempts).run(
                build = { attempt -> runAttempt(image, command, packerDir, logs.newAttempt(name, attempt)) },
                afterFailure = { attempt, failed, willRetry ->
                    eventBus.emit(
                        Event.Ami.BuildAttemptFailed(
                            template = name,
                            attempt = attempt,
                            maxAttempts = maxAttempts,
                            sessionDropped = failed.failure is PackerFailure.SessionDropped,
                            willRetry = willRetry,
                            reason = failed.failure.reason,
                            logPath = failed.logFile.absolutePath,
                        ),
                    )
                    // The builder of an attempt that runs again is always torn down before the next
                    // launches its own; only the last failed builder is kept for --keep-on-error.
                    handleBuildFailure(keepUp && !willRetry)
                },
            )
        when (outcome) {
            is PackerBuildOutcome.Succeeded -> logger.info { "Packer build succeeded" }
            is PackerBuildOutcome.Failed -> exitProcess(1)
        }
    }

    /**
     * Runs one `packer build` in a new container, with its debug log at [logFile], and classifies a
     * failure from that log. A failure to run the container at all (the Docker daemon, or a build
     * that outlived [Constants.Packer.BUILD_TIMEOUT_MINUTES]) is a build error, never retried.
     */
    private fun runAttempt(
        image: String,
        command: List<String>,
        packerDir: File,
        logFile: File,
    ): PackerBuildOutcome =
        runCatchingException { execute(image, command, packerDir, logFile) }
            .fold(
                onSuccess = { result ->
                    when {
                        result.isSuccess -> PackerBuildOutcome.Succeeded
                        else -> PackerBuildOutcome.Failed(PackerFailure.fromLog(readLog(logFile)), logFile)
                    }
                },
                onFailure = { error ->
                    logger.error(error) { "Packer container failed" }
                    PackerBuildOutcome.Failed(PackerFailure.BuildError(error.messageOrString()), logFile)
                },
            )

    private fun readLog(logFile: File): String = if (logFile.exists()) logFile.readText() else ""

    /**
     * Whether the EASY_DB_LAB_BUILD_KEEP_ON_FAILURE env var is set to a truthy value. Lets builds
     * triggered indirectly (e.g. by profile setup) keep the instance up on failure without a flag.
     */
    private fun keepOnErrorFromEnv(): Boolean =
        System.getenv(Constants.Environment.BUILD_KEEP_ON_FAILURE)?.lowercase() in setOf("1", "true", "yes", "on")

    /**
     * On build failure, capture diagnostics from the kept instance (packer ran with
     * -on-error=abort) and surface them, then tear the instance down — unless [keepUp] is set
     * (--keep-on-error / env), in which case it is left running for manual debugging.
     *
     * Best-effort: any failure resolving/SSHing/terminating the instance degrades gracefully to
     * printing manual SSH instructions, so a build failure is never made worse.
     */
    private fun handleBuildFailure(keepUp: Boolean) {
        val instanceId =
            runCatching {
                val vpcId = vpcService.findVpcByName(Constants.Vpc.PACKER_VPC_NAME) ?: return@runCatching null
                // The packer VPC only ever holds the in-flight build instance.
                vpcService.findInstancesInVpc(vpcId).firstOrNull()
            }.getOrNull()

        if (instanceId == null) {
            println("Build failed. Could not locate the build instance to capture/clean up.")
            return
        }

        val ip = runCatching { ec2InstanceService.describeInstances(listOf(instanceId)).firstOrNull()?.publicIp }.getOrNull()
        if (!ip.isNullOrBlank()) {
            captureAndPrintDiagnostics(ip, instanceId)
        }

        if (keepUp) {
            println(
                """

                Build instance $instanceId left running (--keep-on-error / EASY_DB_LAB_BUILD_KEEP_ON_FAILURE).
                SSH in:  ssh -i ${secretKeyFile.absolutePath} ubuntu@${ip ?: "<instance-ip>"}
                Terminate when done: aws ec2 terminate-instances --instance-ids $instanceId
                """.trimIndent(),
            )
        } else {
            runCatching { vpcService.terminateInstances(listOf(instanceId)) }
                .onSuccess { println("Build instance $instanceId terminated (set --keep-on-error to keep it for debugging).") }
                .onFailure { println("WARNING: failed to terminate build instance $instanceId: ${it.message}") }
        }
    }

    /**
     * SSHes into the kept build instance and prints the most useful failure diagnostics.
     * Best-effort: if the instance is unreachable, prints manual SSH instructions instead.
     */
    private fun captureAndPrintDiagnostics(
        ip: String,
        instanceId: String,
    ) {
        val cmd =
            "echo '--- df / ---'; df -h /; " +
                "echo '--- /tmp/jdk-install.log (tail) ---'; tail -60 /tmp/jdk-install.log 2>/dev/null; " +
                "echo '--- /var/log/apt/term.log (tail) ---'; sudo tail -100 /var/log/apt/term.log 2>/dev/null; " +
                "echo '--- dmesg (tail) ---'; sudo dmesg 2>/dev/null | tail -40"
        runCatching {
            val host = Host(public = ip, private = "", alias = "packer-build", availabilityZone = "", instanceId = instanceId)
            val response = remoteOps.executeRemotely(host, cmd, output = false)
            println("==== build instance diagnostics ($ip) ====")
            println(response.text)
            println("==========================================")
        }.onFailure {
            println("Could not auto-capture diagnostics from $ip (${it.message}).")
            println("SSH in manually: ssh -i ${secretKeyFile.absolutePath} ubuntu@$ip")
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
