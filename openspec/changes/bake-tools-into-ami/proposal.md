## Why

The easy-db-lab container currently ships with helm and kubectl baked in (via a custom base image in `docker/Dockerfile`) so that K8s tooling commands can run locally inside the container. This is unnecessary complexity: the AMI already runs on the cluster nodes, helm and kubectl are cluster-management tools, and the container's real job is to hold state (AWS credentials, `state.json`, kubeconfig). Moving all cluster tooling into the AMI eliminates the custom base image, simplifies the Jib build, and makes local dev and CI tooling requirements disappear entirely.

Upgrading the base AMI from Ubuntu 24.04 to 26.04 LTS is included here since it also touches packer configuration and is a natural pairing.

## What Changes

- **Add helm, kubectl, and cilium CLI to packer AMI scripts** — tools are installed on the cluster nodes at AMI build time.
- **Switch `HelmService`, `KubectlService`, and `CiliumService` to run via SSH** — instead of `ProcessBuilder` on the laptop/container, commands are executed remotely on the control node via `RemoteOperationsService`.
- **Delete `docker/Dockerfile` and `.github/workflows/publish-base-image.yml`** — the custom base image no longer exists. Jib builds directly on top of `eclipse-temurin:21-jre`.
- **Update Jib configuration** — switch `from.image` back to `eclipse-temurin:21-jre`; remove any helm/kubectl references from Jib config.
- **Upload kit scripts to control node before execution** — scripts under `<kit>/bin/` are uploaded via `RemoteOperationsService.uploadDirectory()` and executed remotely on the control node. The existing upload mechanism already exists.
- **Upgrade base AMI OS from Ubuntu 24.04 (Noble) to 26.04 LTS** — update `base.pkr.hcl` source AMI filter to the 26.04 LTS AMI pattern.
- **Remove any Terraform references** from the codebase (none expected, but audit and clean up).
- **Update `CLAUDE.md` architecture principle** — revise the `RemoteOperationsService` section to reflect the new tool-execution boundary.

## Capabilities

### New Capabilities

- `ami-tooling`: Tools baked into the AMI — helm, kubectl, and cilium CLI are installed at AMI build time on all cluster nodes. No cluster tooling is required on the developer machine or container.

### Modified Capabilities

- `ami-building`: Ubuntu base OS upgraded from 24.04 to 26.04 LTS.
- `container-wrapper`: Container no longer includes helm or kubectl. Jib builds directly from `eclipse-temurin:21-jre`; the custom base image and its publishing workflow are removed.

## Impact

- **`packer/base/`** — new install scripts for helm, kubectl, and cilium CLI; updated Ubuntu 26.04 AMI filter.
- **`src/main/kotlin/.../services/HelmService.kt`** — refactor from `ProcessBuilder` to `RemoteOperationsService` SSH execution.
- **`src/main/kotlin/.../services/KubectlService.kt`** — same refactor.
- **`src/main/kotlin/.../services/CiliumService.kt`** — same refactor; also migrate from Helm-based install to `cilium install` CLI.
- **`src/main/kotlin/.../services/WorkloadStepExecutor.kt`** (or equivalent) — kit scripts uploaded via SSH before execution.
- **`docker/Dockerfile`** — deleted.
- **`.github/workflows/publish-base-image.yml`** — deleted.
- **`build.gradle.kts` (Jib config)** — `from.image` reverted to `eclipse-temurin:21-jre`.
- **`CLAUDE.md`** — architecture section updated to reflect remote tool-execution boundary.
- **Tests** — existing `HelmService`/`CiliumService` tests must be updated; integration tests will need a test container image that includes helm/kubectl/cilium CLI to mirror the AMI.
