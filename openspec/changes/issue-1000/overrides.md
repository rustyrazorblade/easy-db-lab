## Overrides existing behavior

### networking: SOCKS Proxy (MODIFIED)

**Currently:** "The `env.sh` environment file MUST NOT start the proxy itself. It SHALL read `SOCKS5_PROXY_PORT` from the proxy state file written by the CLI so that shell wrappers use the same port as the Kotlin CLI, rather than hardcoding a port." Scenario "Proxy port exported to shell wrappers from state file": sourcing `env.sh` populates `SOCKS5_PROXY_PORT` from the JSON state file. Teardown kills the process and deletes `.socks5-proxy-state`. The proxy persists "until `down` is called".

**This change:** The CLI is the only proxy manager; `env.sh` and the wrappers never start or stop it. Shell-side tools read the port only from the proxy env file, never from the JSON state file and never with a default port. The proxy persists until `down` or `stop-socks`. Start, reuse, and restart also record the port in the env file; teardown also removes it. `.socks5-proxy-state` is written atomically. The old scenario is replaced by "Shell tools read the port from the env file"; "Proxy state file is written atomically" is added.

### networking: SOCKS Proxy Routes Only Cluster-Internal Traffic (MODIFIED)

**Currently:** "only the clients that need the tunnel (the Kubernetes client and the cluster HTTP client) configure the SOCKS proxy explicitly."

**This change:** The explicit clients also include the workspace tool wrappers, each for its own call only. Wrapped tools send all their traffic through the tunnel, public URLs included; every other shell tool, `aws` included, gets no proxy settings. Adds scenarios "Unwrapped tools in a shell step get no proxy settings" and "Shell-side routing never touches the JVM proxy properties".

### networking: Local kubectl/helm invoked by kit shell steps route through the tunnel via a per-command kubeconfig proxy-url (REMOVED)

**Currently:** With a published port, kit shell steps get a temporary kubeconfig copy whose cluster entry carries `proxy-url: socks5://127.0.0.1:<port>`; only `kubectl`/`helm` use the tunnel; other tools such as `curl` go direct; the copy is deleted when the command ends.

**This change:** Removed — no replacement of the `proxy-url` mechanism. Shell steps get the unmodified workspace kubeconfig by absolute path and the tool wrappers on `PATH`. `curl` is now wrapped and goes through the tunnel on a SOCKS cluster.

### workload-runner: Workload scripts are executed with cluster state as environment variables (MODIFIED)

**Currently:** `KUBECONFIG` is "Path to kubeconfig" (the relative `kubeconfig`); `PATH` is inherited as is.

**This change:** `KUBECONFIG` is the absolute path to the workspace kubeconfig; `PATH` starts with `<workspace>/bin`; the wrappers are written first; the script does not start if the kubeconfig is missing.

### cluster-lifecycle: Cluster Initialization (MODIFIED)

**Currently:** `init` creates the configuration in any directory.

**This change:** `init` fails and writes nothing when the directory already has a `bin/`. This blocks the source checkout and project directories. With `--clean`, the check runs after the cleanup.

### cluster-lifecycle: Local Cleanup (MODIFIED)

**Currently:** Cleanup removes "state files, SSH config, and cached configuration."

**This change:** Cleanup also removes the proxy env file, the six tool wrappers, and the marker, by name; it keeps other files in `bin/` and removes `bin/` only when empty.

### cluster-lifecycle: Cluster State Restore (MODIFIED)

**Currently:** Restore recovers configuration from S3 and rebuilds the local environment.

**This change:** Restore also writes the tool wrappers, their marker, and the proxy env file.

### env-file-config: Scripts source a gitignored .env file at project root (MODIFIED)

**Currently:** "Both `bin/easy-db-lab` and `bin/end-to-end-test` SHALL source `$PROJECT_ROOT/.env`", with a scenario that runs `bin/end-to-end-test`.

**This change:** Only `bin/easy-db-lab`; the scenario runs `bin/easy-db-lab`.

### env-file-config: .env.example documents all supported variables (MODIFIED)

**Currently:** `.env.example` lists the variables of `bin/easy-db-lab` and `bin/end-to-end-test`.

**This change:** Only `bin/easy-db-lab`.

### end-to-end-testing: all twelve requirements (REMOVED)

**Currently:** "End-to-end test runner", "Feature flags for optional services", "Breakpoint and resume support", "Infrastructure provisioning steps", "S3 backup verification", "Cassandra validation steps", "Spark/EMR validation steps", "ClickHouse validation steps", "OpenSearch validation steps", "Observability stack validation", "Error handling with interactive recovery", and "Step listing" describe `bin/end-to-end-test`, which runs with the repository root as the workspace.

**This change:** Removed — no replacement. The script is deleted; lab test plans run through the `/easy-db-lab:plan`, `/easy-db-lab:run`, and `agent-test` skills, each in its own workspace.

## Conflicts with other in-flight changes

- **`ssm-ssh-transport`** (ADDED "SSH transport over SSM Session Manager" in `networking`). It does not modify any requirement this change modifies or removes, and the transport behavior is compatible: this change requires the wrappers to work the same on `ssm`. One scenario of that ADDED requirement goes stale, though: "Shell helpers work unchanged over SSM" says "**WHEN** the user runs `ssh db0`, a `c0` alias, or **starts the SOCKS proxy after `source env.sh`**". This change removes `start-socks5` from `env.sh`, so `env.sh` can no longer start the proxy. That clause directly contradicts this change's "SOCKS Proxy" text ("`env.sh` and the wrappers MUST NOT start or stop it"). This is a genuine, if narrow, incompatibility. Resolution: archive `ssm-ssh-transport` first, then add to this change a MODIFIED copy of "SSH transport over SSM Session Manager" whose scenario reads "runs `ssh db0`, a `c0` alias, or `easy-db-lab start-socks`". This delta cannot carry that MODIFIED block until then, because the requirement is not in the baseline spec yet. Its requirement text also says the generated SSH configuration is "used by the SOCKS proxy and by the shell helpers in `env.sh`", which stays true (`ssh`/`scp`/`rsync` helpers). Its scenario "each host entry's `Hostname` line still immediately follows its `Host` line" loses its only shell reader (`start-socks5`'s `grep -A 1`); the ordering is harmless and stays.
- **`issue-966`** and **`issue-970`** touch `cluster-lifecycle`, but only "Cluster Teardown" (both MODIFIED) and two ADDED compactor requirements. This change modifies "Cluster Initialization", "Local Cleanup", and "Cluster State Restore". No conflict. `issue-966`'s tasks edited `bin/end-to-end-test`, which this change deletes; that work is already merged and nothing in its delta depends on the script.
- **`issue-971`**, **`issue-992`**, and **`ssm-packer-builds`** touch none of `networking`, `typed-install-steps`, `workload-runner`, `kit-lifecycle-hooks`, `cluster-lifecycle`, `end-to-end-testing`, or `env-file-config`. None found.
