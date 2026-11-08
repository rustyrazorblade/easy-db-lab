## Context

Verified at b1c5056b. Paths are under `src/main/kotlin/com/rustyrazorblade/easydblab/`.

`Context.workingDirectory` exists, and about 40 sites use it correctly: `Status`, `StatusCache`, `KitStatusCommand`, `MetricsStreamModule`, `ProxyModule`, `ProcessSocksProxyService`, `Init`, `Clean`, `DownloadConfig`, `KitHookExecutor`, `KitInstallCommand`, `KitRunnerCommand`, `ClusterConfigurationService`, `BackupRestoreService`, `Repl`. Every kit `ProcessBuilder` sets `.directory(...)`.

In the CLI process the workspace always equals `user.dir`, so the bug never fires there. It fires when a `Context` is built for a different directory.

| # | Site | File |
|---|---|---|
| 1 | `configuration/ClusterStateManager.kt:25`, bound bare at `di/ConfigurationModule.kt:11` | `state.json` |
| 2 | `services/K8sClientProvider.kt:31` | `kubeconfig` |
| 3–7 | `commands/SetupInstance.kt:36,45,126,135,142` | `environment.sh`, `cassandra-jmx-rules.yaml`, `setup_instance.sh` |
| 8 | `commands/cassandra/Start.kt:84` | `axonops-workbench.json` |
| 9 | `commands/cassandra/UpdateConfig.kt:95`, through `Version.file = File(versionString)` (`Version.kt:20`) | `<version>/` conf directory |
| 10 | `providers/ssh/DefaultRemoteOperationsService.kt:123`, `uploadDirectory(host, Version)` | same, but it has no `src/main` callers |
| 11–12 | `commands/cassandra/profiler/ProfilingFetch.kt:35`, `ProfilingFlamegraph.kt:60` | `profiles/` |
| 13 | `services/aws/EMRSparkService.kt:73`, bound with no `logsDir` at `providers/aws/AWSModule.kt:242` | `logs/<cluster>/<step>` |
| + | `commands/UploadAuthorizedKeys.kt:54` | the scratch file `authorized_keys_extra`, never removed |

## Decisions (owner, 2026-11-08)

**D1. Keep `Context.workingDirectory`.**
- Each site resolves `File(context.workingDirectory, NAME)`, and Koin bindings derive from `get<Context>().workingDirectory`, as `ProxyModule.kt:53` does.
- Rejected: a `Workspace` value class. It would touch about 50 sites with no behaviour change. Reconsider it if #1040 needs two workspaces in one process.

**D2. `check()` in `load()`.**
- The message is "No cluster state at <abs>. Run the command from the cluster's workspace, or run 'init' there first."
- It reaches the user through `Command.ExecutionError`, in the `check()` style `load()` already uses (`:56`).
- Rejected: a new typed event, because every `load()` caller would then need the event bus.

**D3. Typed paths resolve against the process directory.**
- `upload-keys <dir>` follows the same convention as `cassandra build [<dir>]`.

**D4. Delete the dead `uploadDirectory(host, Version)` overload.**
- Its only purpose was the relative path. Architect recommendation; it is not a design fork.

**D5. The compiler is the guard.**
- `Context`, `ClusterStateManager` and `EMRSparkService` get required parameters, and the root `CLAUDE.md` gets a standing rule.
- Rejected:
  - ArchUnit: it cannot see constant arguments.
  - A detekt custom rule: heavy.
  - A source-text scan: banned.

## Test approach

- Never set `user.dir` in a test. It is JVM-global, and the JDK caches it for relative `File` resolution, so setting it has no effect.
- "Current directory differs from the workspace" comes for free: `TestContextFactory` gives a temp workspace W, while the test JVM runs in the repo, D.
- Load the **production** `configurationModule` over the test `Context`. A test-only rebinding would prove nothing.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| A test still rebinds `ClusterStateManager`, so the production binding goes untested | The two workaround overrides are deleted, and the state test uses the production module. |
| Callers construct `Context` with no workspace | The required parameter is a compile error. `ProcessSocksProxyMessageTest.kt:32` is updated. |
| A caller catches `FileNotFoundException` from `load()` | None exist in `src/main` (checked). Tests asserting it are updated. |
| Textual conflict with PR #1078 in `ServicesModule.kt` | Adjacent lines; trivial to resolve. |
| `require(isAbsolute)` meets #898's `mkdirs()` in `Context.init` | Add only the `require`; #898 is not folded in. |
