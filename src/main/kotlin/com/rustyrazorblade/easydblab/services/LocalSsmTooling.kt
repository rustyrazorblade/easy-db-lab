package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import java.time.Duration

/**
 * A local program the `ssm` SSH transport cannot work without.
 *
 * @property executable the name looked up on PATH
 * @property installHint how to install it, shown when it is not on PATH
 */
enum class SsmTool(
    val executable: String,
    val installHint: String,
) {
    AwsCli(Constants.Ssm.AWS_CLI, Constants.Ssm.AWS_CLI_INSTALL_HINT),
    SessionManagerPlugin(Constants.Ssm.SESSION_MANAGER_PLUGIN, Constants.Ssm.PLUGIN_INSTALL_HINT),
}

/**
 * Why one [SsmTool] cannot carry a session. The cases need different fixes, so each keeps what was
 * seen: a tool that is not installed needs installing, while one that is installed but fails or
 * hangs needs its own output read, and an install hint would only mislead.
 */
sealed interface SsmToolFault {
    /** The tool that could not be run. */
    val tool: SsmTool

    /** The executable is not on this machine's PATH. */
    data class NotFound(
        override val tool: SsmTool,
    ) : SsmToolFault

    /** `<tool> --version` ran and exited non-zero; [output] is what it printed on stdout and stderr. */
    data class Failed(
        override val tool: SsmTool,
        val exitCode: Int,
        val output: String,
    ) : SsmToolFault

    /** `<tool> --version` did not exit within [timeout] and was killed. */
    data class TimedOut(
        override val tool: SsmTool,
        val timeout: Duration,
    ) : SsmToolFault
}

/**
 * Reports which [SsmTool]s cannot run on this machine, and why.
 *
 * `up` asks before it creates any AWS resource. Without these tools every SSH connection under
 * the `ssm` transport fails, and that would otherwise surface only after instances were running,
 * as a readiness timeout.
 */
fun interface LocalSsmTooling {
    /** Never throws; returns one fault per tool that could not be run, in [SsmTool] order. */
    fun faults(): List<SsmToolFault>
}

/**
 * Default [LocalSsmTooling]: a tool is usable when `<tool> --version` exits 0. A missing binary,
 * a non-zero exit, and a hang are all faults, since none of them can carry a session.
 */
class DefaultLocalSsmTooling(
    private val runner: LocalCliRunner = DefaultLocalCliRunner,
    private val timeout: Duration = Duration.ofSeconds(Constants.Ssm.TOOL_CHECK_TIMEOUT_SECONDS),
) : LocalSsmTooling {
    override fun faults(): List<SsmToolFault> = SsmTool.entries.mapNotNull(::check)

    private fun check(tool: SsmTool): SsmToolFault? =
        when (val result = runner.run(listOf(tool.executable, "--version"), timeout)) {
            is LocalCliResult.BinaryNotFound -> SsmToolFault.NotFound(tool)
            is LocalCliResult.TimedOut -> SsmToolFault.TimedOut(tool, timeout)
            is LocalCliResult.Completed ->
                if (result.exitCode == 0) {
                    null
                } else {
                    SsmToolFault.Failed(tool, result.exitCode, listOf(result.stdout, result.stderr).joinToString("\n").trim())
                }
        }
}
