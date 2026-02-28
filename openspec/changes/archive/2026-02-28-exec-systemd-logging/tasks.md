## 1. Infrastructure: Log Directory and OTel Receiver

- [x] 1.1 Add `mkdir -p /var/log/easydblab/tools` to `packer/cassandra/install/prepare_instance.sh`
- [x] 1.2 Add `filelog/tools` receiver to `otel-collector-config.yaml` watching `/var/log/easydblab/tools/*.log` with `source: tool-runner` attribute
- [x] 1.3 Wire `filelog/tools` into the `logs/local` pipeline receivers list

## 2. Restructure Exec as Parent Command

- [x] 2.1 Create `commands/exec/Exec.kt` as a parent command with subcommands, following the `Spark` pattern
- [x] 2.2 Create `commands/exec/ExecRun.kt` — move current exec logic here, add `--bg` and `--name` flags
- [x] 2.3 Create `commands/exec/ExecList.kt` — list running `edl-exec-*` units on target hosts
- [x] 2.4 Create `commands/exec/ExecStop.kt` — stop a named `edl-exec-*` unit on target hosts
- [x] 2.5 Delete old `commands/Exec.kt` and update `CommandLineParser.kt` registration

## 3. Implement systemd-run Execution

- [x] 3.1 Implement foreground execution in `ExecRun`: `systemd-run --wait` with `StandardOutput=file:/var/log/easydblab/tools/<name>.log`, then cat the log file to display output
- [x] 3.2 Implement background execution in `ExecRun`: `systemd-run` (no `--wait`) with file output, print unit name and return
- [x] 3.3 Implement unit name derivation: use `--name` if provided, otherwise `<tool>-<epoch>` from first command token
- [x] 3.4 Implement `ExecList`: SSH to target hosts, run `systemctl list-units 'edl-exec-*' --no-pager`, display results
- [x] 3.5 Implement `ExecStop`: SSH to target hosts, run `sudo systemctl stop edl-exec-<name>`, report result

## 4. Events

- [x] 4.1 Add events for exec lifecycle: `ToolStarted(host, unitName, command)`, `ToolStopped(host, unitName)`, `ToolList(host, units)`

## 5. Testing

- [x] 5.1 Write tests for unit name derivation (user-provided name, auto-derived from command)
- [x] 5.2 Write tests for systemd-run command construction (foreground vs background, file paths)
- [x] 5.3 Run full test suite to confirm no regressions

## 6. Documentation

- [x] 6.1 Update `docs/reference/opentelemetry.md` with tool runner log collection section
- [x] 6.2 Update exec command documentation with new subcommands and `--bg`/`--name` flags
- [x] 6.3 Update CLAUDE.md observability section to mention filelog/tools receiver
