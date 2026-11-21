package com.rustyrazorblade.easydblab.commands

import org.jline.keymap.KeyMap
import org.jline.reader.LineReader
import org.jline.reader.Reference
import org.jline.shell.Shell
import org.jline.shell.widget.CommandTailTipWidgets
import org.jline.terminal.Terminal
import java.nio.file.Path

/**
 * Builds the REPL's jline shell over [terminal] for the commands in [group].
 *
 * Every line reaches its command as typed: [LiteralLineDispatcher] turns off the dispatcher's
 * expansion and operators, and the line reader's `!` history events (`!!`, `!$`) are turned off
 * here, because a line often carries a remote shell command. History is kept in [historyFile],
 * and Alt+S toggles the command descriptions under the input line.
 */
object ReplShell {
    fun build(
        terminal: Terminal,
        group: ReplCommandGroup,
        historyFile: Path,
    ): Shell =
        Shell
            .builder()
            .terminal(terminal)
            .dispatcher(LiteralLineDispatcher(terminal))
            .prompt(PROMPT)
            .groups(group)
            .helpCommands(true)
            .historyFile(historyFile)
            .variable(LineReader.LIST_MAX, MAX_COMPLETION_CANDIDATES)
            .onReaderReady { reader ->
                reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION)
                enableTailTips(reader, group)
            }.build()

    /** Command descriptions under the input line, toggled with Alt+S. */
    private fun enableTailTips(
        reader: LineReader,
        group: ReplCommandGroup,
    ) {
        val widgets =
            CommandTailTipWidgets(
                reader,
                { line -> group.describe(line.args()) },
                TAILTIP_DESCRIPTION_LINES,
                CommandTailTipWidgets.TipType.COMPLETER,
            )
        widgets.enable()
        reader.keyMaps[LineReader.MAIN]?.bind(Reference(TAILTIP_TOGGLE_WIDGET), KeyMap.alt('s'))
    }

    private const val PROMPT = "easy-db-lab> "
    private const val MAX_COMPLETION_CANDIDATES = 50
    private const val TAILTIP_DESCRIPTION_LINES = 5

    /** jline's widget that turns the tail tips on and off. */
    private const val TAILTIP_TOGGLE_WIDGET = "tailtip-toggle"
}
