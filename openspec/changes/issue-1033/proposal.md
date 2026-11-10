## Why

Part of #1032 (local execution environment), spike break B9. Thirteen sites in 8 files open a workspace file by a bare relative name, so it resolves against the JVM's current directory instead of `Context.workingDirectory`. Any process whose directory differs from the workspace reads or writes the wrong cluster: the local provisioner, the server, the REPL and test harnesses. Two tests already work around it by rebinding `ClusterStateManager`. On AWS today it is a latent bug. `download-config` and `update-config` already disagree when the two directories differ.

## What Changes

- `Context.workingDirectory` has no `user.dir` default, and must be absolute (`require` in `init`). `user.dir` is read in exactly one place, the `ContextFactory` default. `Context.forCli` takes the directory, and the unused `forRepl` is deleted.
- `ClusterStateManager` takes a **required** state file, bound from `File(get<Context>().workingDirectory, CLUSTER_STATE)`. `load()` fails with the absolute path when the file is missing.
- `K8sClientProvider(clusterStateManager, workspace)` reads `<workspace>/kubeconfig`, the same pattern as `LocalClusterTooling` in PR #1078.
- These sites resolve `File(context.workingDirectory, NAME)`:
  - `SetupInstance` (5 sites)
  - `cassandra start` (`axonops-workbench.json`)
  - `cassandra update-config`, through removal of `Version.file`, the same way `download-config` resolves it
  - `profiling fetch` and `profiling flamegraph` (`profiles/`)
- `EMRSparkService` gets the workspace `logs/` directory with no default.
- `upload-keys` writes its scratch file to a temp file and deletes it.
- The unused `uploadDirectory(host, Version)` overload and its two test fakes are deleted.
- The workaround overrides in `HelpTopicCommandLinesTest` and `CommandLineParserTest` are removed. They become regression tests of the production binding.
- Docs:
  - root `CLAUDE.md`: a standing rule, "never open a workspace file by a bare relative name"
  - `configuration/CLAUDE.md`: the `state.json` note

## Capabilities

### Modified Capabilities

- `cluster-lifecycle`: workspace files resolve against the workspace, and a missing state names its absolute path.

## Impact

- When the current directory is the workspace (the normal CLI case), every path is the same, made absolute.
- User-visible changes:
  - the missing-state error names an absolute path
  - `upload-keys` no longer leaves `authorized_keys_extra` behind
- No new type. The compiler is the regression guard: the required parameters make the relative form unreachable by default.

## Out of scope

- How the workspace is chosen (#1040).
- Multi-workspace commands.
- #986 (concurrent `state.json` writes).
- #898 (`Context.init` side effects).
- `TemplateVariables`' `KUBECONFIG="kubeconfig"`: templates anchor it themselves, and `KitProcessEnvironment` overrides it with the absolute path.
