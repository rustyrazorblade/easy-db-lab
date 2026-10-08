## 1. Packer templates

- [x] 1.1 Add an `ssh_interface` variable (default empty) to `packer/base/base.pkr.hcl` and `packer/cassandra/cassandra.pkr.hcl`
- [x] 1.2 Set `ssh_interface = var.ssh_interface != "" ? var.ssh_interface : null` in each `amazon-ebs` source

## 2. SSM-capable Packer image

- [x] 2.1 Packaged Dockerfile resource: `hashicorp/packer:full-1.16.1` pinned by digest + `gcompat` + the Session Manager plugin `1.2.835.0` from AWS's `.deb`, verified against a pinned SHA-256 per architecture, with a `--version` check
- [x] 2.2 `DockerClientInterface.buildImage` / `DefaultDockerClient` (docker-java `buildImageCmd`, pull base)
- [x] 2.3 `Docker.buildImage(dockerfile, imageTag)` writing a temp build context, with typed build events
- [x] 2.4 `PackerImage`: image per transport; content-hash tag; build only when missing

## 3. Packer runner

- [x] 3.1 `Packer` runs in `PackerImage`'s image and passes `-var ssh_interface=session_manager` under `ssm`
- [x] 3.2 `PackerImage` registered in Koin (`dockerModule`) and injected into `Packer`

## 4. Tests

- [x] 4.1 `PackerImage` unit tests: direct pulls the stock image and never builds; ssm builds when missing, reuses when present; tag tracks the Dockerfile content
- [x] 4.2 `Packer` argument test: `ssh_interface` passed only under `ssm`
- [x] 4.3 Integration test: the packaged Dockerfile builds and its `session-manager-plugin --version` runs

## 5. Docs

- [x] 5.1 `docs/user-guide/network-connectivity.md`: AMI builds under `ssm`
- [x] 5.2 `docs/getting-started/setup.md`: AMI build note for `ssm`
- [x] 5.3 `providers/CLAUDE.md` / root `CLAUDE.md` "Building AMIs" note
