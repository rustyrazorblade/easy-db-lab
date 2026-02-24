package com.rustyrazorblade.easydblab

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Image
import com.github.dockerjava.api.model.PullResponseItem
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.docker.ContainerExecutor
import com.rustyrazorblade.easydblab.docker.ContainerIOManager
import com.rustyrazorblade.easydblab.docker.ContainerStateMonitor
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException
import java.io.PipedInputStream
import java.time.Duration

// Interface for Docker client operations to improve testability
interface DockerClientInterface {
    fun listImages(
        name: String,
        tag: String,
    ): List<Image>

    fun pullImage(
        name: String,
        tag: String,
        callback: PullImageResultCallback,
    )

    fun createContainer(imageTag: String): ContainerCreationCommand

    fun attachContainer(
        containerId: String,
        stdin: PipedInputStream,
        callback: ResultCallback.Adapter<Frame>,
    )

    fun startContainer(containerId: String)

    fun inspectContainer(containerId: String): InspectContainerResponse

    fun removeContainer(
        containerId: String,
        removeVolumes: Boolean,
    )
}

// Default implementation that delegates to the actual DockerClient
class DefaultDockerClient(
    private val dockerClient: DockerClient,
) : DockerClientInterface {
    override fun listImages(
        name: String,
        tag: String,
    ): List<Image> = dockerClient.listImagesCmd().withImageNameFilter("$name:$tag").exec()

    override fun pullImage(
        name: String,
        tag: String,
        callback: PullImageResultCallback,
    ) {
        val pullCommand =
            if (tag.isNotBlank()) {
                dockerClient.pullImageCmd(name).withTag(tag)
            } else {
                dockerClient.pullImageCmd(name)
            }
        pullCommand.exec(callback)
    }

    override fun createContainer(imageTag: String): ContainerCreationCommand =
        ContainerCreationCommand(dockerClient.createContainerCmd(imageTag))

    override fun attachContainer(
        containerId: String,
        stdin: PipedInputStream,
        callback: ResultCallback.Adapter<Frame>,
    ) {
        dockerClient
            .attachContainerCmd(containerId)
            .withStdIn(stdin)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .exec(callback)
    }

    override fun startContainer(containerId: String) {
        dockerClient.startContainerCmd(containerId).exec()
    }

    override fun inspectContainer(containerId: String): InspectContainerResponse = dockerClient.inspectContainerCmd(containerId).exec()

    override fun removeContainer(
        containerId: String,
        removeVolumes: Boolean,
    ) {
        dockerClient
            .removeContainerCmd(containerId)
            .withRemoveVolumes(removeVolumes)
            .exec()
    }
}

// Wrapper class for container creation commands to make testing easier
class ContainerCreationCommand(
    private val command: com.github.dockerjava.api.command.CreateContainerCmd,
) {
    fun withCmd(commands: List<String>): ContainerCreationCommand {
        command.withCmd(commands)
        return this
    }

    fun withEnv(env: Array<String>): ContainerCreationCommand {
        // Spread operator is required to pass array to vararg parameter
        @Suppress("SpreadOperator")
        command.withEnv(*env)
        return this
    }

    fun withStdinOpen(open: Boolean): ContainerCreationCommand {
        command.withStdinOpen(open)
        return this
    }

    fun withVolumes(volumes: List<Volume>): ContainerCreationCommand {
        command.withVolumes(volumes)
        return this
    }

    fun withHostConfig(config: HostConfig): ContainerCreationCommand {
        command.withHostConfig(config)
        return this
    }

    fun withWorkingDir(dir: String): ContainerCreationCommand {
        require(dir.isNotBlank()) { "Working directory cannot be blank" }
        command.withWorkingDir(dir)
        return this
    }

    fun exec(): com.github.dockerjava.api.command.CreateContainerResponse = command.exec()
}

// Utility for getting user ID, extracted for testability
interface UserIdProvider {
    fun getUserId(): Int
}

class DefaultUserIdProvider : UserIdProvider {
    override fun getUserId(): Int {
        val idQuery =
            ProcessBuilder("id", System.getProperty("user.name"))
                .start()
                .inputStream
                .bufferedReader()
                .readLine()
        val matches = "uid=(\\d*)".toRegex().find(idQuery)

        return matches?.groupValues?.get(1)?.toInt() ?: 0
    }
}

data class VolumeMapping(
    val source: String,
    val destination: String,
    val mode: AccessMode,
) {
    companion object {
        val log = KotlinLogging.logger {}
    }

    init {
        require(source.isNotBlank()) { "Volume source path cannot be blank" }
        require(destination.isNotBlank()) { "Volume destination path cannot be blank" }
        log.info { "Creating volume mapping $source to $destination, mode: $mode" }
    }
}

/**
 * Main class for Docker container operations.
 *
 * This class provides a high-level interface for running Docker containers with
 * support for volumes, environment variables, and flexible output handling.
 *
 * The class has been refactored to support:
 * - Modular output handling (console, logger, buffer, custom)
 * - Better testability through dependency injection
 * - Background execution with proper output management
 * - Cleaner separation of concerns
 *
 * Example usage:
 * ```kotlin
 * // Create a Docker instance with console output
 * val docker = Docker(context, dockerClient)
 *
 * // Add volumes and environment variables
 * docker.addVolume(VolumeMapping("/host/path", "/container/path", AccessMode.rw))
 *       .addEnv("MY_VAR=value")
 *
 * // Run a container
 * val result = docker.runContainer(
 *     Containers.UBUNTU,
 *     mutableListOf("echo", "Hello World"),
 *     "/workspace"
 * )
 * ```
 *
 * For background execution with logger output:
 * ```kotlin
 * val docker = DockerFactory.createLoggerDocker(context, dockerClient)
 * // Container output will go to the logger instead of console
 * ```
 *
 * @param context The execution context
 * @param dockerClient The Docker client interface for container operations
 * @param userIdProvider Provider for getting the current user ID (for permissions)
 * Events are emitted via the EventBus for user-facing output.
 */
class Docker(
    val context: Context,
    private val dockerClient: DockerClientInterface,
    private val userIdProvider: UserIdProvider = DefaultUserIdProvider(),
) : KoinComponent {
    private val eventBus: EventBus by inject()

    companion object {
        private const val CONTAINER_ID_DISPLAY_LENGTH = 12
        private val DEFAULT_MAX_WAIT_TIME = Duration.ofMinutes(10)

        val log = KotlinLogging.logger {}
    }

    private val volumes = mutableListOf<VolumeMapping>()
    private val env = mutableListOf<String>()

    fun addVolume(vol: VolumeMapping): Docker {
        require(vol.source.isNotBlank()) { "Volume source path cannot be blank" }
        require(vol.destination.isNotBlank()) { "Volume destination path cannot be blank" }
        log.info { "adding volume: $vol" }
        volumes.add(vol)
        return this
    }

    fun addEnv(envList: String): Docker {
        require(envList.isNotBlank()) { "Environment variable cannot be blank" }
        env.add(envList)
        return this
    }

    fun exists(
        name: String,
        tag: String,
    ): Boolean {
        val result = dockerClient.listImages(name, tag)
        return result.isNotEmpty()
    }

    internal fun pullImage(container: Containers) {
        try {
            return pullImage(container.containerName, container.tag)
        } catch (e: com.github.dockerjava.api.exception.DockerException) {
            throw DockerException("Error pulling image ${container.containerName}:${container.tag}", e)
        } catch (e: IOException) {
            throw DockerException("IO error pulling image ${container.containerName}:${container.tag}", e)
        }
    }

    /**
     * Tag is required here, otherwise we pull every tag
     * and that isn't fun
     */
    private fun pullImage(
        name: String,
        tag: String,
    ) {
        require(name.isNotBlank()) { "Image name cannot be blank" }
        require(tag.isNotBlank()) { "Image tag cannot be blank" }
        log.debug { "Creating pull object" }

        val callback =
            object : PullImageResultCallback() {
                override fun awaitStarted(): ResultCallback.Adapter<PullResponseItem>? {
                    log.info { "Pulling image $name" }
                    return super.awaitStarted()
                }

                override fun onNext(item: PullResponseItem?) {
                    item?.progressDetail?.let { detail ->
                        val current = detail.current
                        val total = detail.total
                        if (current != null && total != null) {
                            eventBus.emit(Event.Docker.ImagePulling(current, total))
                        }
                    }
                    return super.onNext(item)
                }
            }

        dockerClient.pullImage(name, tag, callback)
        callback.awaitCompletion()

        log.info { "Finished pulling $name" }
    }

    fun runContainer(
        container: Containers,
        command: MutableList<String>,
        workingDirectory: String,
        maxWaitTime: Duration = DEFAULT_MAX_WAIT_TIME,
    ): Result<String> {
        if (!exists(container.containerName, container.tag)) {
            pullImage(container)
        }

        return runContainer(container.imageWithTag, command, workingDirectory, maxWaitTime)
    }

    /**
     * Run a Docker container with the specified configuration.
     *
     * This method executes a container with the given image, command, and working directory.
     * It handles the complete lifecycle: creation, attachment, execution, and cleanup.
     *
     * The output is handled through the configured OutputHandler, allowing for:
     * - Console output (default)
     * - Logger output (for background execution)
     * - Buffered output (for testing)
     * - Custom output handling
     *
     * @param imageTag The Docker image tag to use (e.g., "ubuntu:latest")
     * @param command The command to execute in the container
     * @param workingDirectory The working directory inside the container
     * @return Result containing the stdout output on success, or an exception on failure
     * @throws IllegalArgumentException if imageTag is blank or command is empty
     */
    internal fun runContainer(
        imageTag: String,
        command: MutableList<String>,
        workingDirectory: String,
        maxWaitTime: Duration = DEFAULT_MAX_WAIT_TIME,
    ): Result<String> {
        require(imageTag.isNotBlank()) { "Image tag cannot be blank" }
        require(command.isNotEmpty()) { "Command list cannot be empty" }

        // Use a buffered handler to capture output for the return value
        val bufferedHandler = BufferedOutputHandler()

        val dockerCommandBuilder = dockerClient.createContainer(imageTag)

        // Get user ID using the injected provider for testability
        val userId = userIdProvider.getUserId()
        log.debug { "user id: $userId" }
        check(userId > 0) { "User ID must be positive, got: $userId" }

        env.add("HOST_USER_ID=$userId")

        log.info {
            "Starting docker container with command $command and environment variables: $env"
        }

        dockerCommandBuilder
            .withCmd(command)
            .withEnv(env.toTypedArray())
            .withStdinOpen(true)

        if (volumes.isNotEmpty()) {
            setupVolumes(dockerCommandBuilder)
        }

        if (workingDirectory.isNotEmpty()) {
            val workDirMsg = "Setting working directory inside container to $workingDirectory"
            eventBus.emit(Event.Docker.ExecutionWorkDir(workDirMsg))
            bufferedHandler.handleMessage(workDirMsg)
            dockerCommandBuilder.withWorkingDir(workingDirectory)
        }

        val dockerContainer = dockerCommandBuilder.exec()
        val containerId = dockerContainer.id.substring(0, CONTAINER_ID_DISPLAY_LENGTH)
        val startMsg = "Starting $imageTag container ($containerId)"
        eventBus.emit(Event.Docker.ExecutionStarting(startMsg))
        bufferedHandler.handleMessage(startMsg)

        // Use the new modular components
        val ioManager = ContainerIOManager(dockerClient)
        val executor = ContainerExecutor(dockerClient)
        val stateMonitor = ContainerStateMonitor()

        // Attach to container and set up IO
        val framesRead = ioManager.attachToContainer(dockerContainer.id, true)

        log.info { "Starting container with command $command" }

        // Start container and wait for completion
        val containerState = executor.startAndWaitForCompletion(dockerContainer.id, maxWaitTime)

        // Report final state
        stateMonitor.reportFinalState(containerState, framesRead)

        // Clean up
        ioManager.close()
        executor.removeContainer(dockerContainer.id)

        val returnCode = containerState.exitCodeLong?.toInt() ?: -1
        return if (returnCode == 0) {
            Result.success(bufferedHandler.stdout)
        } else {
            Result.failure(Exception("Non zero response returned."))
        }
    }

    // Extracted methods for better testability

    private fun setupVolumes(dockerCommandBuilder: ContainerCreationCommand) {
        val volumesList = mutableListOf<Volume>()
        val bindesList = mutableListOf<Bind>()

        volumes.forEach {
            val containerVolume = Volume(it.destination)
            volumesList.add(containerVolume)
            bindesList.add(Bind(it.source, containerVolume, it.mode))
        }

        dockerCommandBuilder
            .withVolumes(volumesList)
            // This api changed a little when docker-java was upgraded, they stopped proxying this call
            // now we have to explicitly set a host config and add the binds there
            .withHostConfig(HostConfig().withBinds(bindesList))
    }
}

class DockerException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)
