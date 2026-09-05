## 1. Packer AMI Scripts

- [x] 1.1 Look up Ubuntu 26.04 LTS AMI name filter — codename is **Resolute**, filter: `ubuntu/images/*ubuntu-resolute-26.04-${arch}-server-*`
- [x] 1.2 Update `packer/base/base.pkr.hcl` source AMI filter from 24.04 (noble) to 26.04 (resolute)
- [x] 1.3 Create `packer/base/install/install_helm.sh` — pin to the same version as currently in `docker/Dockerfile`
- [x] 1.4 Create `packer/base/install/install_kubectl.sh` — pin to current version
- [x] 1.5 Look up cilium CLI version matching Cilium CNI 1.19.4 — v0.19.4
- [x] 1.6 Create `packer/base/install/install_cilium_cli.sh` — pin to matched version
- [x] 1.7 Add the three new install scripts to `base.pkr.hcl` provisioners list
- [x] 1.8 Verify packer scripts pass local packer test (`./gradlew testPackerBase`)

## 2. Refactor HelmService

- [x] 2.1 Change `HelmService.upgradeInstall()` to run `helm` on the control node via `RemoteOperationsService.executeRemotely()`
- [x] 2.2 Remove `ProcessBuilder` / local `KUBECONFIG` injection from `HelmService`
- [x] 2.3 Update/add integration test for `HelmService` using a test container with helm installed (or skip if no practical test harness — document why)

## 3. Refactor KubectlService

- [x] 3.1 Change `KubectlService` to run `kubectl` on the control node via `RemoteOperationsService.executeRemotely()`
- [x] 3.2 For manifest apply: upload the manifest file via `RemoteOperationsService.upload()`, then run `kubectl apply -f <remote-path>`
- [x] 3.3 Remove local `ProcessBuilder` / `KUBECONFIG` injection from `KubectlService`
- [x] 3.4 Update existing `KubectlService` tests

## 4. Refactor CiliumService

- [x] 4.1 Replace `HelmService.upgradeInstall()` call in `CiliumService` with `RemoteOperationsService.executeRemotely()` running `cilium install --version <version>`
- [x] 4.2 Pass any required cilium CLI flags (API server host/port, Hubble settings) as `cilium install` arguments
- [x] 4.3 Update `CiliumService` tests

## 5. Container Build Cleanup

- [x] 5.1 Update `build.gradle.kts` Jib config: set `from.image = "eclipse-temurin:21-jre"`
- [x] 5.2 Remove any helm/kubectl references from Jib configuration
- [x] 5.3 Delete `docker/Dockerfile`
- [x] 5.4 Delete `.github/workflows/publish-base-image.yml`
- [x] 5.5 Update `docker/CLAUDE.md` (or remove it if the directory is gone)

## 6. Audit and Documentation

- [x] 6.1 Search codebase for any Terraform references and remove them
- [x] 6.2 Update `CLAUDE.md` architecture note for `RemoteOperationsService` to reflect new tool-execution boundary
- [x] 6.3 Update `providers/CLAUDE.md` or `services/CLAUDE.md` if they document local helm/kubectl execution patterns

## 7. Integration Test

- [ ] 7.1 Build new AMI using updated packer scripts
- [ ] 7.2 Run `bin/test -p clickhouse` against a cluster provisioned with the new AMI
- [ ] 7.3 Verify cilium install succeeds via cilium CLI (not Helm)
- [ ] 7.4 Verify ClickHouse lifecycle passes end-to-end
