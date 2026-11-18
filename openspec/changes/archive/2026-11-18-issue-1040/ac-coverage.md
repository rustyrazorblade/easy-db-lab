# Acceptance criteria and risks → scenarios

| Issue criterion / risk | Covering scenario(s) |
| --- | --- |
| `init --local` with no AWS configuration makes no AWS call and shows no prompt | cluster-lifecycle: "init --local with no AWS configuration" |
| No data-directory option → under the workspace; a given path is recorded | cluster-lifecycle: "Default data directory is under the workspace", "A given data directory is recorded" |
| Local `bin/` wrappers set no proxy variables, need no tunnel | networking: "A local workspace's wrappers set no proxy variables", "Local init writes direct mode to the env file" |
| A proxy-requiring command in a local workspace connects directly | networking: "A proxy-requiring command in a local workspace connects directly", "socks start in a local workspace" |
| AWS workspaces behave as today | execution-environment: "EC2 + K3s resolves to today's behaviour", "A state with no environment reads as EC2 + K3s", "An AWS workspace still requires the profile"; cluster-lifecycle: "AWS init is unchanged", "up on EC2 + K3s is unchanged"; networking: "An AWS workspace keeps its tunnel" |
| AWS-only commands fail fast and name the environment | execution-environment: "cassandra use in a local workspace", "spark in a local workspace", "build-image outside a workspace" |
| Unknown recorded value fails clearly, no crash | execution-environment: "An unknown environment fails clearly" |
| No profile for local `init`/`up`/`down`/kit commands | execution-environment: "Kit commands in a local workspace need no profile"; cluster-lifecycle: "up in a local workspace before the local provisioner exists" |
| Kubeconfig is a first-class variant (amendment) | execution-environment: "Each environment round-trips", "A cluster the user brings resolves to portable forms" |
| Consumers read capability data, not names (K1) | execution-environment: "Consumers read capability data…" requirement, "The cluster tooling form follows the recorded environment" |
| Risk: stale `single` after REPL `init` | execution-environment: "init inside the REPL switches the contract" |
| Risk: env file drifts from state | networking: "The env file follows the recorded environment" |
| Risk: hidden `User` loads on local paths | execution-environment: "Kit commands in a local workspace need no profile" (plus Koin graph test, task 4.5) |
| Risk: `aws-account` faked by an override | execution-environment: "aws-account cannot be overridden" |
| D3: K3s in Docker reached by loopback port-forward | cluster-lifecycle: "Local init records port-forward exposure" |
| Docs, `help provisioning`, networking and cluster-lifecycle specs | Tasks 8.1-8.3; spec deltas in this change |

Intentional exclusions:
- Namespace (#1094) and datacenter (#1097) flags: contract fields only, with fixed values (D1).
- Local kit `start` reaching storage: needs the #1035 Volume rendering. This change removes only the profile requirement.
- Docker Desktop host reachability: D3 (owner, 2026-11-16) chose PortForward on loopback, so the host never dials container IPs; #1041 builds the cluster.
