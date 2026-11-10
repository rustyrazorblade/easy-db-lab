## 1. State

- [x] 1.1 Test first, with the production `configurationModule` over the test `Context`. Save, then reload: `W/state.json` holds the state, and `D/state.json` never exists. With no state, `load()` fails naming the absolute path. One `CommandExecutor` run checks that the `ExecutionError` carries it.
- [x] 1.2 `ClusterStateManager` takes a required state file, bound from `Context`. Add the missing-state `check()`. Remove the overrides in `HelpTopicCommandLinesTest:38` and `CommandLineParserTest:46`.

## 2. Kubeconfig

- [x] 2.1 Test first: with `W/kubeconfig` carrying a distinctive server URL, `createClient(...).configuration.masterUrl` is that URL. With no kubeconfig, the error names `W/kubeconfig`.
- [x] 2.2 `K8sClientProvider(clusterStateManager, workspace)`. Update the binding and `K8sServiceTest.kt:40`.

## 3. Context

- [x] 3.1 Remove the `user.dir` default from `Context`, and add `require(workingDirectory.isAbsolute)`. `user.dir` is read only in the `ContextFactory` default. `forCli(dir)` takes the directory; delete `forRepl`. Update the callers.

## 4. Command sites

- [x] 4.1 Test first: a recording `RemoteOperationsService` shows that every local path `SetupInstance` and `cassandra update-config` uses is under W. `cassandra start` emits `AxonOpsWorkbenchInfo` only when `W/axonops-workbench.json` exists.
- [x] 4.2 Route `SetupInstance` and `Start`. Add a constant for `setup_instance.sh`.
- [x] 4.3 Remove `Version.file`. `UpdateConfig` resolves `context.workingDirectory.resolve(localDir)`. Delete the unused `uploadDirectory(host, Version)` overload and its fakes (`TestModules.kt:330`, `UpTestFixture.kt:264`).

## 5. Profiling and EMR logs

- [x] 5.1 Test first: fetched profile chunks land in `W/profiles/<host>`. Replace the `outputDir` test seam with the workspace path.
- [x] 5.2 Bind `EMRSparkService` with `File(workspace, Constants.EMR.LOCAL_LOGS_DIR).toPath()`, with no default.

## 6. upload-keys

- [ ] 6.1 Test first: the uploaded content is the joined keys, no `authorized_keys_extra` remains in D or W, and a relative `<dir>` resolves against D.
- [ ] 6.2 Use `Files.createTempFile`, deleted in `finally`.

## 7. Docs and verify

- [ ] 7.1 Root `CLAUDE.md`: add the standing rule "never open a workspace file by a bare relative name; resolve it against `Context.workingDirectory`". Update the `configuration/CLAUDE.md` `state.json` note.
- [ ] 7.2 Run `ktlintFormat`, `detekt` on JDK 21, and `./gradlew test` in a subagent, with no new `@Suppress`, threshold change or baseline entry.
