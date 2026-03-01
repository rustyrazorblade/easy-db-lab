## Context

The `exec` command (`commands/Exec.kt`) runs arbitrary shell commands on remote hosts via SSH. Output is printed to the console and lost. There is no log capture, no background execution support, and no way to query what was run after the fact.

The OTel Collector DaemonSet on every node already has a `filelog/system` receiver watching `/var/log/**/*.log` and shipping to VictoriaLogs. Adding a dedicated receiver for tool logs is straightforward.

The command is registered as a top-level command in `CommandLineParser.kt` via `Exec::class`. It uses `HostOperationsService.withHosts()` for multi-host execution and `remoteOps.executeRemotely()` for SSH.

## Goals / Non-Goals

**Goals:**
- Every command run via `exec` is logged to a file and shipped to VictoriaLogs automatically
- Background execution for long-running monitoring tools (inotifywait, tcpdump, etc.)
- List and stop running background tools
- Tool logs queryable in VictoriaLogs after cluster teardown (via existing `logs backup`)

**Non-Goals:**
- Interactive/TTY commands (these are one-shot or long-running producers of output)
- Streaming background tool output back to the CLI in real time
- Custom VictoriaLogs queries or report generation (use existing `logs query`)

## Decisions

### Use systemd-run for all exec commands

**Decision:** All commands run via `exec` use `systemd-run` on the remote host, with stdout/stderr routed to `/var/log/easydblab/tools/<name>.log`.

**Alternatives considered:**
- Keep direct SSH for foreground, only use systemd-run for background — inconsistent behavior, foreground commands wouldn't be logged
- Pipe to `tee` — fragile, doesn't survive SSH disconnect for background jobs
- Write a wrapper script on the node — another moving part to install and maintain

**Rationale:** `systemd-run` provides clean process management, survives SSH disconnects, and routes output to files natively via `StandardOutput=file:...`. Using it for both foreground (`--wait`) and background gives consistent behavior: every command is logged regardless of mode.

### Foreground: systemd-run --wait

**Decision:** Foreground commands (default, no `--bg`) use `systemd-run --wait --pipe` which blocks until the command completes and pipes output back to the SSH session.

**Rationale:** The user sees output in real time just like today's `exec`, but the output is also captured to the log file. `--pipe` passes stdout/stderr through the SSH connection. `--property=StandardOutput=file:...` and `--property=StandardCopy=...` would not show output live — instead, we use `--pipe` and additionally run `tee` or rely on the tool's own output. Actually, `--pipe` and file logging are mutually exclusive in systemd-run.

**Revised approach:** For foreground, use `systemd-run --wait` with `StandardOutput=file:...`, then after the command completes, `cat` the log file back to the user. This ensures the log file exists for OTel to pick up, and the user still sees the output. The tradeoff is output appears after completion rather than streaming, which is acceptable for short-lived foreground commands.

For commands where streaming matters, users should use `--bg` and then `tail -f` the log or query VictoriaLogs.

### Unit naming convention

**Decision:** systemd units are named `edl-exec-<name>` where `<name>` is either user-provided (`--name`) or derived as `<tool>-<epoch>`. The tool name is extracted from the first token of the command.

**Examples:**
- `exec --bg --name watch-imports -- inotifywait -m /mnt/db1` → `edl-exec-watch-imports`
- `exec --bg -- inotifywait -m /mnt/db1` → `edl-exec-inotifywait-1709142000`
- `exec -- ls /mnt/db1` → `edl-exec-ls-1709142000`

**Rationale:** The `edl-exec-` prefix makes it easy to list/filter all exec-managed units. User-provided names are more memorable for stop/list workflows.

### Restructure Exec as parent command with subcommands

**Decision:** Convert `Exec` from a single command to a parent command with subcommands, following the `Spark` pattern:
- `exec run` — execute a command (default behavior, replaces current `exec`)
- `exec list` — list running background tools
- `exec stop` — stop a named background tool

**Alternatives considered:**
- Keep exec flat with `exec --list` and `exec --stop` flags — mixing actions and flags is awkward, especially since `exec` takes positional parameters for the command

**Rationale:** Subcommands are cleaner and match existing patterns (`spark submit`, `spark status`, `logs query`). PicoCLI supports this natively.

### Log directory: /var/log/easydblab/tools/

**Decision:** Create `/var/log/easydblab/tools/` during Packer build (in `prepare_instance.sh`), owned by root with world-readable permissions.

**Rationale:** systemd-run creates units as root, so the directory needs to be writable by root. The OTel filelog receiver runs in a K8s DaemonSet with hostPath mounts, so files need to be readable. Creating during Packer build ensures it exists on all nodes.

### OTel filelog/tools receiver

**Decision:** Add a `filelog/tools` receiver to the OTel Collector config watching `/var/log/easydblab/tools/*.log` with `source: tool-runner` attribute. Wire into the existing `logs/local` pipeline.

**Rationale:** Follows the existing pattern (filelog/system, filelog/cassandra). The `source: tool-runner` attribute makes it easy to filter tool logs in VictoriaLogs queries. The OTel DaemonSet's hostPath volume mount already covers `/var/log/`.

## Risks / Trade-offs

- **Foreground output is post-completion** → Using `systemd-run --wait` with file output means the user sees results after the command finishes, not streaming. This is fine for short commands (`ls`, `df`) but less ideal for longer-running foreground commands. Mitigation: use `--bg` for anything long-running.
- **systemd-run availability** → All Ubuntu-based AMIs have systemd. Not a real risk for this project.
- **Log file accumulation** → Tool logs persist in `/var/log/easydblab/tools/` until cluster teardown. No rotation needed since clusters are ephemeral. If this becomes an issue, logrotate can be added later.
- **Unit name collisions** → If the same auto-derived name is generated twice in the same second (unlikely), systemd-run will fail. Mitigation: user can provide `--name` to avoid this. Could also add a random suffix if needed.
