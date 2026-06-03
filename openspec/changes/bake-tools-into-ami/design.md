## Context

Currently, `HelmService`, `KubectlService`, and `CiliumService` run their respective CLI tools via `ProcessBuilder` on the local machine (laptop or container), with `KUBECONFIG` injected as an env var. This requires helm and kubectl to be installed inside the easy-db-lab container (via `docker/Dockerfile` → `ghcr.io/rustyrazorblade/easy-db-lab-base`), and prevents using the cilium CLI (which is not in the container).

The cluster nodes are AMI-backed EC2 instances. Tools installed at AMI build time are available on every node from first boot, with no per-cluster setup. Moving tool execution to the control node via SSH removes all local tool requirements and opens the door to using the native cilium CLI instead of Helm for Cilium installation.

## Goals / Non-Goals

**Goals:**
- Helm, kubectl, and cilium CLI run on the control node, not locally.
- Container image is built directly from `eclipse-temurin:21-jre` with no extra tooling.
- `docker/Dockerfile` and `publish-base-image.yml` are deleted.
- Cilium is installed via `cilium install` (cilium CLI) instead of `helm upgrade --install`.
- Base AMI upgraded from Ubuntu 24.04 to 26.04 LTS.

**Non-Goals:**
- Moving *all* SSH operations to run locally (that would be the opposite direction).
- Supporting cilium CLI features beyond cluster installation (hubble, observability remain as-is).
- Changing how kit scripts work for kits that already use `type: shell` steps — those already rely on `kubectl` being available in the environment, and will naturally use the version on the control node.

## Decisions

### Decision: SSH via RemoteOperationsService, not subprocess

`HelmService.upgradeInstall()`, `KubectlService.apply()`, and `CiliumService.install()` will call `RemoteOperationsService.executeRemotely()` on the control node instead of `ProcessBuilder`.

**Alternatives considered:**
- Keep local execution, install tools in CI/devcontainer only — rejected; doesn't remove container complexity and makes fresh dev setups require manual tool installs.
- Run tools in a sidecar K8s pod — rejected; overcomplicated for a CLI tool.

### Decision: Upload scripts to control node before execution

Kit scripts under `<kit>/bin/` are uploaded via `RemoteOperationsService.uploadDirectory()` to a temp directory on the control node before being executed. The existing `upload()` / `uploadDirectory()` / `executeRemotely()` API supports this without new abstractions.

**Alternative:** Bundle all kit scripts into the AMI — rejected; kit scripts are user-editable templates that live in the cluster working directory after `install`. They must be sent at runtime, not baked in.

### Decision: Cilium CLI replaces Helm for Cilium installation

`cilium install` handles version pinning, waits for readiness, and provides better diagnostics than the Helm chart. The Helm chart for Cilium is essentially a thin wrapper; using the CLI directly is the recommended path in Cilium docs.

Cilium CLI version is pinned in the packer script (matching current Cilium version `1.19.4`).

**Alternative:** Keep Helm, just run it remotely — rejected; the user explicitly asked to switch to cilium CLI.

### Decision: Single packer install script per tool

Each tool gets its own install script under `packer/base/install/`:
- `install_helm.sh`
- `install_kubectl.sh`
- `install_cilium_cli.sh`

These follow the existing pattern (`install_k3s.sh`, `install_tailscale.sh`, etc.) and are idempotent.

### Decision: Ubuntu 26.04 LTS

The source AMI filter in `base.pkr.hcl` is updated to `ubuntu-*-26.04-*-server-*`. The Cassandra AMI filter is unchanged (it builds on the base AMI, not raw Ubuntu). The Ubuntu upgrade is a clean cut — clusters are ephemeral, no migration needed.

## Risks / Trade-offs

[Risk: Tool version skew] Tools are pinned at AMI build time. If a cluster uses an old AMI, it may run an older helm/kubectl than expected. → Mitigation: AMI version is already a cluster-level invariant; document that a fresh AMI build is needed when tool versions matter.

[Risk: SSH latency for helm/kubectl calls] Each helm or kubectl invocation now has SSH round-trip overhead. → Acceptable: these are cluster setup operations (seconds to minutes), not hot-path CLI commands.

[Risk: Ubuntu 26.04 compatibility] New OS version may have package name changes or service behavior differences. → Mitigation: run the full integration test suite against a new AMI before merging.

[Risk: cilium CLI version mismatch] `cilium install` must match the Cilium version running on the cluster. → Mitigation: pin both the CLI version and the Cilium version to the same value in packer, mirroring what `CiliumService` does today.

## Migration Plan

1. Add packer install scripts for helm, kubectl, cilium CLI.
2. Update `base.pkr.hcl` to Ubuntu 26.04.
3. Build and publish new AMI.
4. Refactor `HelmService`, `KubectlService`, `CiliumService` to SSH.
5. Update kit script execution to upload+run remotely.
6. Update Jib config (`from.image = eclipse-temurin:21-jre`).
7. Delete `docker/Dockerfile` and `publish-base-image.yml`.
8. Run integration test (clickhouse + cilium install) on a fresh cluster using the new AMI.
9. Update `CLAUDE.md` architecture note.

Rollback: The old AMI is still available. Reverting `build.gradle.kts` and restoring the Dockerfile is sufficient to go back — no cluster-state migration needed.

## Open Questions

- Ubuntu 26.04 codename and exact AMI name filter string (verify against AWS AMI catalog at implementation time — my knowledge cutoff predates the 26.04 release).
- Which cilium CLI version corresponds to Cilium 1.19.4? (Check cilium/cilium-cli release tags at implementation time.)
