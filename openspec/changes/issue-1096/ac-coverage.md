| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | Baseline namespace: three recorded absent, `init` prints which components that disables | `Namespace enforcing baseline`; `Baseline namespace disables privileged components` | Covered by the probe and the init report. The `init --kubeconfig` call site lands with #1082 (owner split, 2026-11-13). |
| AC2 | The recorded capability follows an override | `Override removes a capability`; `Override adds a capability the probe found absent`; `Unknown capability name`; `Conflicting overrides` | Covered |
| AC3 | `up` reports every stage needing an absent capability with `StageSkipped` | `Component list matches up's gating` | Partial: no stage requires the four new capabilities until #1092 and #1091 add the gates; the existing `StageSkipped` path covers them unchanged. |
| AC4 | `status` lists the capabilities | `status shows present and absent` | Covered |
| Scope | AWS and local declare all four | `AWS declares the four capabilities`; `Local Docker has no node SSH` | Covered. Local declares no `NODE_SSH`: Docker nodes have no SSH (#1039 renames it `NODE_ACCESS`). |
| Scope | Recorded in the workspace and published in `cluster-config` | `Recorded set is used`; `cluster-config lists present capabilities` | Covered |
| Scope | Code asks for a capability, never an environment name | `Kit variable follows NODE_SSH`; requirement text | Covered |
| Risk | A DaemonSet dry-run passes under Pod Security enforce | requirement text (Pods only) and `Namespace enforcing baseline` | Covered |
| Risk | A dry-run in a missing namespace returns 404 | `Missing namespace` | Covered |
| Risk | A webhook that refuses dry run | `Webhook refuses dry run` | Covered |
| Risk | A rejection that is not about the tested field | `Baseline Pod rejected` | Covered |
| Risk | A capability change rolls workloads needlessly | `Unchanged set keeps the config hash` | Covered |
| Risk | AWS behavior changes | `No recorded set reads as AWS`; `AWS kit processes keep node SSH` | Covered |
| Exclusion | Policy engines that ignore dry-run, mutating webhooks | none | Accepted in design (override plus #1102) |
