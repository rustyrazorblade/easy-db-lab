| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | Workspace W, current directory D: `W/state.json` is used and nothing is created in D | `cluster-lifecycle: State is read and written in the workspace` | ✅ |
| AC2 | The Kubernetes client uses `W/kubeconfig` | `cluster-lifecycle: Kubernetes client uses the workspace kubeconfig` | ✅ |
| AC3 | When D equals W, nothing changes | `cluster-lifecycle: Today's normal case is unchanged` | ✅ |
| AC4 | A missing state names the absolute `W/state.json` | `cluster-lifecycle: Missing state names its absolute path` | ✅ |
| Scope | Every other workspace file found the same way | `cluster-lifecycle: Other workspace files resolve against the workspace` | ✅ |
| Risk | `upload-keys` leaves a scratch file in D | `cluster-lifecycle: Uploading authorized keys leaves no file behind` | ✅ |
| Risk | Typed paths change meaning | `cluster-lifecycle: A typed path resolves where it was typed` | ✅ |
| Risk | A test rebinding hides the production binding | `cluster-lifecycle: State is read and written in the workspace` (task 1.1 uses the production module) | ✅ |
| Risk | Textual conflict with PR #1078 | — | ⚠️ Excluded: merge mechanics, no behaviour |
