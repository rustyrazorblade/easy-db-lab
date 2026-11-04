# Acceptance criteria coverage for issue-1034

| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC1 | Every typed step kind succeeds through the local form with no SSH (except platform-pvs / platform-pvs-delete) | cluster-tooling: Every typed step runs through the local form with no SSH; typed-install-steps: Local form runs helm on the host | ✅ Covered |
| AC1 (part) | platform-pvs and platform-pvs-delete with no SSH | — | ⏭ Moved to #1039 (owner decision D4): node filesystem commands |
| AC2 | AWS still runs the commands on the control node over SSH with the control-node KUBECONFIG | cluster-tooling: AWS keeps running the tools on the control node; ami-tooling: helm upgrade --install runs remotely; ami-tooling: kubectl apply runs remotely; typed-install-steps: No local helm required | ✅ Covered |
| AC3 | A failure in either form carries exit status and stderr | cluster-tooling: Local failure reports status and stderr; cluster-tooling: SSH failure reports status and stderr | ✅ Covered |
| AC4 | No temporary manifest file remains after a local apply | cluster-tooling: Local apply writes no temporary file | ⚠ Structural, not a runtime test: `LocalClusterTooling` pipes stdin and creates no manifest or values file.  The wrapper install in `KitProcessEnvironment.applyTo` writes atomically through a temporary file inside `<workspace>/bin`; that is existing behaviour, not a manifest or values file.  The SSH form's cleanup is covered by `SshClusterToolingTest` (injected temporary directory empty after a successful and a failed run) |
| AC5 | The local form never sets `socksProxyHost` / `socksProxyPort` | cluster-tooling: SOCKS properties stay unset | ✅ Covered |
| R1 | SSH command strings drift and regress AWS | cluster-tooling: AWS keeps running the tools on the control node | ✅ Covered |
| R2 | `helm repo add` writes the user's helm home | cluster-tooling: Local helm uses the workspace kubeconfig and helm home | ✅ Covered |
| R2 (part) | The local form starts the workspace wrapper `<workspace>/bin/<tool>`, never a bare tool name, so a SOCKS workspace routes helm and kubectl through the tunnel | cluster-tooling: The local form runs in the kit process environment (requirement text) | ⚠ Checked by review, not by a test (owner cut D-15, 2026-11-03): the rule is a code fact in `LocalClusterTooling.start`.  The integration tier would not catch a bare name, because its workspace is in `direct` mode and the host's own tools reach the cluster; the unit tier fails on it only through the stand-in wrapper |
| R3 | `helm --values -` unsupported by the pinned helm | ami-tooling: helm upgrade --install runs locally | ✅ Covered |
| R4 | Integration test silently skips without helm, kubectl or network | — | ⚠ Exclusion: a test-harness rule, not product behaviour; recorded in tasks 4.1 (fail clearly, never skip) |
| R5 | Fabric8 steps locally need direct network mode | — | ⏭ Out of scope: #1040; the test writes the key itself (D5) |
| R6 | Temporary input file left on the control node | cluster-tooling: SSH input file removed after a failure | ✅ Covered |
| D3 | SSH form is the default binding | cluster-tooling: The SSH form is the default binding | ✅ Covered |
