# Acceptance criteria coverage for issue-1034

| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC1 | Every typed step kind succeeds through a local form with no SSH | — | ⏭ Moved to #1163 (owner decision D1): the local form ships there |
| AC1 (part) | platform-pvs and platform-pvs-delete with no SSH | — | ⏭ Moved to #1039 (D5): node filesystem commands |
| AC2 | AWS still runs the commands on the control node over SSH with the control-node KUBECONFIG | cluster-tooling: The tools run on the control node; ami-tooling: helm upgrade --install runs remotely; ami-tooling: kubectl apply runs remotely; typed-install-steps: No local helm required | ✅ Covered |
| AC3 | A failure carries exit status and stderr | cluster-tooling: SSH failure reports status and stderr | ✅ Covered |
| AC4 | No temporary input file remains after an apply | cluster-tooling: SSH input file removed after a failure | ✅ Covered |
| AC5 | No `socksProxyHost` / `socksProxyPort` | — | ✅ Holds by construction: the SSH form opens no local client connection |
| R1 | SSH command strings drift and regress AWS | cluster-tooling: The tools run on the control node; cluster-tooling: A kit variable with shell metacharacters | ✅ Covered |
| R2 | Temporary input file left on the control node | cluster-tooling: SSH input file removed after a failure | ✅ Covered |
| D1 | SSH form is the default binding | cluster-tooling: The SSH form is the default binding | ✅ Covered |
