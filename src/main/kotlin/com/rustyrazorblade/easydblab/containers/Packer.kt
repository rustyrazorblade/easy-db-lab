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
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.FileUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
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
    private val user: User by inject()
    private val awsCredentialsManager by lazy { AWSCredentialsManager(context.profileDir, user) }

    private var containerWorkingDir = Constants.Paths.LOCAL_MOUNT
    private var logger = KotlinLogging.logger {}
    private var release = false

    companion object {
        private const val PACKER_TIMEOUT_MINUTES = 60L
    }

    // todo include the region defined in the profile
    fun build(
        name: String,
        buildArgs: BuildArgsMixin,
    ) {
        buildInternal(name, buildArgs.region, buildArgs.arch, buildArgs.release)
    }

    private fun buildInternal(
        name: String,
        region: String,
        arch: Arch,
        isRelease: Boolean,
    ) {
        require(name.isNotBlank()) { "Build name cannot be blank" }
        require(region.isNotBlank()) { "Build region cannot be blank" }

        val command =
            mutableListOf(
                "build",
                "-var",
                "region=$region",
                "-var",
                "arch=${arch.type}",
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
                exitProcess(1)
            }
            result.isSuccess -> {
                logger.info { "Packer build succeeded" }
            }
        }
    }

    private fun execute(vararg commands: String): Result<String> {
        require(commands.isNotEmpty()) { "Commands cannot be empty" }

        docker.pullImage(Containers.PACKER)

        val args = commands.toMutableList()

        var localPackerPath = context.packerHome + directory

        require(directory.isNotBlank()) { "Directory cannot be blank" }

        if (!File(localPackerPath).exists()) {
            eventBus.emit(Event.Error("packer directory not found: $localPackerPath"))
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
        var creds = Constants.Paths.CREDENTIALS_MOUNT

        // Packer builds can take 30+ minutes, especially when building from source
        val packerTimeout = Duration.ofMinutes(PACKER_TIMEOUT_MINUTES)

        return docker
            .addVolume(packerDir)
            .addVolume(
                VolumeMapping(awsCredentialsManager.credentialsPath, creds, AccessMode.ro),
            ).addEnv("${Constants.Packer.AWS_CREDENTIALS_ENV}=$creds")
            .runContainer(Containers.PACKER, args, containerWorkingDir, packerTimeout)
    }
}
