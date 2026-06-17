package com.rustyrazorblade.easydblab.containers

import com.github.dockerjava.api.model.AccessMode
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Containers
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.Docker
import com.rustyrazorblade.easydblab.VolumeMapping
import com.rustyrazorblade.easydblab.commands.mixins.BuildArgsMixin
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.FileUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess

class Packer(
    val context: Context,
    var directory: String,
) : KoinComponent {
    private val docker: Docker by inject { parametersOf(context) }
    private val eventBus: EventBus by inject()
    private val credentialsProvider: AwsCredentialsProvider by inject()
    private val user: User by inject()

    // Used on build failure to capture diagnostics from, and tear down, the kept instance.
    private val vpcService: VpcService by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val remoteOps: RemoteOperationsService by inject()

    private val awsCredentialsManager by lazy { AWSCredentialsManager(context.profileDir, credentialsProvider) }
    private val secretKeyFile by lazy { File(context.profileDir, "secret.pem") }

    private val containerWorkingDir = Constants.Paths.LOCAL_MOUNT
    private val logger = KotlinLogging.logger {}
    private var release = false

    companion object {
        private const val PACKER_TIMEOUT_MINUTES = 60L
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

        val command =
            mutableListOf(
                "build",
                // Always keep the instance on failure so we can capture diagnostics from it
                // before deciding whether to tear it down (see handleBuildFailure).
                "-on-error=abort",
            )

        require(user.keyName.isNotEmpty() && secretKeyFile.exists()) {
            "AWS keypair not configured (key: '${user.keyName}', file: ${secretKeyFile.absolutePath}). Run setup-profile first."
        }

        command.addAll(
            listOf(
                "-var",
                "region=$region",
                "-var",
                "arch=${arch.type}",
                "-var",
                "s3_bucket=${user.s3Bucket}",
                // Build with the user's own AWS keypair so an instance left up via --keep-on-error
                // can be SSH'd into with ${profileDir}/secret.pem.
                "-var",
                "ssh_keypair_name=${user.keyName}",
                "-var",
                "ssh_private_key_file=${Constants.Paths.SSH_KEY_MOUNT}",
            ),
        )

        if (isRelease) {
            // When passing the release flag,
            // we use the release version as the image version.
            // We also make the AMI public.
            release = true
            command.addAll(
                arrayOf("-var", "release_version=${context.version}"),
            )
        }

        command.add(name)

        // refactor to exit with status 1 if the Result is failure
        // Spread operator is required to pass array to vararg parameter
        @Suppress("SpreadOperator")
        val result = execute(*command.toTypedArray())
        when {
            result.isFailure -> {
                logger.error { "Packer build failed: ${result.exceptionOrNull()}" }
                handleBuildFailure(keepUp)
                exitProcess(1)
            }
            result.isSuccess -> {
                logger.info { "Packer build succeeded" }
            }
        }
    }

    /**
     * Whether the EASY_DB_LAB_BUILD_KEEP_ON_FAILURE env var is set to a truthy value. Lets builds
     * triggered indirectly (e.g. by setup-profile) keep the instance up on failure without a flag.
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
            captureAndPrintDiagnostics(ip)
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
    private fun captureAndPrintDiagnostics(ip: String) {
        val cmd =
            "echo '--- df / ---'; df -h /; " +
                "echo '--- /tmp/jdk-install.log (tail) ---'; tail -60 /tmp/jdk-install.log 2>/dev/null; " +
                "echo '--- /var/log/apt/term.log (tail) ---'; sudo tail -100 /var/log/apt/term.log 2>/dev/null; " +
                "echo '--- dmesg (tail) ---'; sudo dmesg 2>/dev/null | tail -40"
        runCatching {
            val host = Host(public = ip, private = "", alias = "packer-build", availabilityZone = "")
            val response = remoteOps.executeRemotely(host, cmd, output = false)
            println("==== build instance diagnostics ($ip) ====")
            println(response.text)
            println("==========================================")
        }.onFailure {
            println("Could not auto-capture diagnostics from $ip (${it.message}).")
            println("SSH in manually: ssh -i ${secretKeyFile.absolutePath} ubuntu@$ip")
        }
    }

    private fun execute(vararg commands: String): Result<String> {
        require(commands.isNotEmpty()) { "Commands cannot be empty" }

        docker.pullImage(Containers.PACKER)

        val args = commands.toMutableList()

        val localPackerPath = context.packerHome + directory

        require(directory.isNotBlank()) { "Directory cannot be blank" }

        if (!File(localPackerPath).exists()) {
            eventBus.emit(Event.Docker.PackerDirectoryNotFound(localPackerPath))
            exitProcess(1)
        }

        val tempDir = createTempDirectory().toFile()
        FileUtils.copyDirectory(File(localPackerPath), tempDir)
        logger.info { "Copied packer files from $localPackerPath to $tempDir" }

        if (!release && directory == Constants.Servers.DATABASE) {
            // if we're doing a C* image, we
            val initial = Path.of(localPackerPath, Constants.Packer.CASSANDRA_VERSIONS_FILE)
            val extras = context.cassandraVersionsExtra.toPath()
            logger.info { "Loading files in $extras" }

            val versions = CassandraVersion.loadFromMainAndExtras(initial, extras)
            val outputFile = File(tempDir, Constants.Packer.CASSANDRA_VERSIONS_FILE)
            CassandraVersion.write(versions, outputFile)
            logger.info { "Written updated versions to $outputFile" }
        }

        logger.info { "Mounting $tempDir to $containerWorkingDir, starting with $args" }

        // mount credentials
        // get the main process and go up a directory
        val packerDir = VolumeMapping(tempDir.absolutePath, containerWorkingDir, AccessMode.ro)
        val creds = Constants.Paths.CREDENTIALS_MOUNT

        // Packer builds can take 30+ minutes, especially when building from source
        val packerTimeout = Duration.ofMinutes(PACKER_TIMEOUT_MINUTES)

        return docker
            .addVolume(packerDir)
            .addVolume(
                VolumeMapping(awsCredentialsManager.credentialsPath, creds, AccessMode.ro),
            )
            // Mount the user's private key so packer authenticates with their keypair
            .addVolume(
                VolumeMapping(secretKeyFile.absolutePath, Constants.Paths.SSH_KEY_MOUNT, AccessMode.ro),
            ).addEnv("${Constants.Packer.AWS_CREDENTIALS_ENV}=$creds")
            .runContainer(Containers.PACKER, args, containerWorkingDir, packerTimeout)
    }
}
