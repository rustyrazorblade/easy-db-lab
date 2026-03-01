## Why

The `exec` command runs arbitrary shell commands on remote hosts but output is ephemeral — it's printed to the console and lost. During database investigations, ad-hoc tools like `inotifywait`, `tcpdump`, or `strace` produce critical debugging data that needs to be preserved in VictoriaLogs alongside all other cluster telemetry. When an orchestrator runs multi-step workflows (start monitoring tools, run a Spark job, tear down the cluster), the investigation data must survive cluster shutdown and be queryable after the fact.

## What Changes

- Rewrite the `exec` command to run all commands via `systemd-run` on remote hosts, routing stdout/stderr to log files under `/var/log/easydblab/tools/`
- Add `--bg` flag for long-running background tools (returns immediately, tool keeps running)
- Add `--name` flag for naming background units; auto-derive from tool name + timestamp if not provided
- Add `exec list` subcommand to show running background tools on target hosts
- Add `exec stop` subcommand to stop a named background tool
- Add a `filelog/tools` receiver to the OTel Collector config to ship tool logs to VictoriaLogs
- Create `/var/log/easydblab/tools/` directory during instance setup

## Capabilities

### New Capabilities

- `exec-logging`: Systemd-backed remote command execution with automatic log capture and background tool lifecycle management

### Modified Capabilities

- `observability`: Add `filelog/tools` receiver to OTel Collector for shipping tool runner logs to VictoriaLogs

## Impact

- `src/main/kotlin/com/rustyrazorblade/easydblab/commands/Exec.kt` — rewrite to use systemd-run, add subcommands (list, stop), add --bg and --name flags
- `src/main/resources/com/rustyrazorblade/easydblab/configuration/otel/otel-collector-config.yaml` — add filelog/tools receiver and wire into logs/local pipeline
- `packer/cassandra/install/prepare_instance.sh` or `setup_instance.sh` — create `/var/log/easydblab/tools/` directory
- Events — new event types for background tool lifecycle (started, stopped, listed)
- Documentation — update exec command reference
