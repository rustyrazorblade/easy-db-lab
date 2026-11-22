## 1. State

- [x] 1.1 Test first, with the production `configurationModule` over the test `Context`. Save, then reload: `W/state.json` holds the state, and `D/state.json` never exists. With no state, `load()` fails naming the absolute path. One `CommandExecutor` run checks that the `ExecutionError` carries it.
- [x] 1.2 `ClusterStateManager` takes a required state file, bound from `Context`. Add the missing-state `check()`. Remove the overrides in `HelpTopicCommandLinesTest:38` and `CommandLineParserTest:46`.

## 2. Kubeconfig

- [x] 2.1 Test first: with `W/kubeconfig` carrying a distinctive server URL, `createClient(...).configuration.masterUrl` is that URL. With no kubeconfig, the error names `W/kubeconfig`.
- [x] 2.2 Already done on main: `K8sClientProvider(context, clientFactory)` resolves the kubeconfig through `WorkspaceKubeconfig`. No change.

## 3. Context

- [x] 3.1 Remove the `user.dir` default from `Context`, and add `require(workingDirectory.isAbsolute)`. `user.dir` is read only in the `ContextFactory` default. `forCli(dir)` takes the directory; delete `forRepl`. Update the callers.

## 4. Command sites

- [x] 4.1 Test first: a recording `RemoteOperationsService` shows that every local path `NodeSetupService` (the per-node setup `setup-instances` and `up` run) uploads is under W, for every server type. `cassandra start` emits `AxonOpsWorkbenchInfo` only when `W/axonops-workbench.json` exists.
- [x] 4.2 `NodeSetupService` takes the `Context` and reads `environment.sh`, `cassandra-jmx-rules.yaml` and `setup_instance.sh` through `workspaceFile`. Route `Start`.
- [x] 4.3 Remove `Version.file` and `Version.localDir`. `DownloadConfig` and `UpdateConfig` resolve `context.workspaceFile(versionString)`. Delete the unused `uploadDirectory(host, Version)` overload and its fakes (`TestModules.kt:330`, `UpTestFixture.kt:264`).

## 5. Profiling and EMR logs

- [x] 5.1 Test first: fetched profile chunks land in `W/profiles/<host>`. Replace the `outputDir` test seam with the workspace path.
- [x] 5.2 Bind `EMRSparkService` in `AwsServicesModule` with `get<Context>().workspaceFile(Constants.EMR.LOCAL_LOGS_DIR).toPath()`, with no default.

## 6. upload-keys

- [x] 6.1 Test first: the uploaded content is the joined keys, no `authorized_keys_extra` remains in D or W, and a relative `<dir>` resolves against D.
- [x] 6.2 Use `Files.createTempFile`, deleted in `finally`.
- [x] 6.3 Test first: with no directory, `upload-keys` reads `W/authorized_keys`. The default is the workspace's directory, not a bare relative name.

## 7. Docs and verify

- [x] 7.1 Root `CLAUDE.md`: add the standing rule "never open a workspace file by a bare relative name; resolve it against `Context.workingDirectory`". Update the `configuration/CLAUDE.md` `state.json` note.
- [x] 7.2 Run `ktlintFormat`, `detekt` on JDK 21, and `./gradlew test` in a subagent, with no new `@Suppress`, threshold change or baseline entry.
