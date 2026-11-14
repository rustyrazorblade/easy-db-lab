package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireDocker
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.BuildArgsMixin
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.services.CommandExecutor
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

/**
 * Build both the base and Cassandra AMI images.
 *
 * Each image is a nested command. The Cassandra image is built on the newest base image, so a
 * failed base build stops `build-image` before the Cassandra build, and either failure makes
 * `build-image` exit non-zero.
 */
@RequireDocker
@RequireProfileSetup
@Command(
    name = "build-image",
    description = ["Build both the base and Cassandra image"],
)
class BuildImage : PicoBaseCommand() {
    @Mixin
    var buildArgs = BuildArgsMixin()

    private val commandExecutor: CommandExecutor by inject()

    override fun execute() {
        runPhase("build-base") { BuildBaseImage().apply { this.buildArgs = this@BuildImage.buildArgs } }
        runPhase("build-cassandra") { BuildCassandraImage().apply { this.buildArgs = this@BuildImage.buildArgs } }
    }

    /**
     * Runs one phase. The executor has already printed the phase's own error, so a failure adds
     * only which phase stopped the build.
     */
    private fun runPhase(
        phase: String,
        command: () -> PicoCommand,
    ) {
        val exitCode = commandExecutor.execute(command)
        if (exitCode != 0) {
            eventBus.emit(Event.Ami.BuildPhaseFailed(phase = phase, exitCode = exitCode))
            throw CommandFailedException("build-image failed in $phase")
        }
    }
}
