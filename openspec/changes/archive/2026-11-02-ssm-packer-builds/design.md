## Context

`Packer` runs `packer build` inside the `hashicorp/packer:full` container (Alpine), with the
operator's AWS credentials file and SSH private key mounted. The templates launch the builder
instance with `iam_instance_profile = "EasyDBLabEC2Role"` and dial its public IP on port 22,
through a security group that admits the operator's `/32`.

Packer has a built-in answer for networks that cannot reach the instance:
`ssh_interface = "session_manager"`. Packer starts an `AWS-StartPortForwardingSession` to the
instance's port 22 and runs its SSH communicator through the resulting local port. It requires
three things: the instance profile carries SSM permissions (done by `ssm-ssh-transport`), the
caller may start the session (also done), and the `session-manager-plugin` binary is on Packer's
PATH. The stock image lacks the plugin.

## Goals / Non-Goals

**Goals:**
- Under the `ssm` transport, every AMI build reaches its builder instance through Session Manager.
- Under `direct`, builds are byte-for-byte unchanged.
- Nothing new to install on the operator's machine for AMI builds: the plugin lives in the image.

**Non-Goals:**
- Removing the Packer security group's port-22 rule (harmless under `ssm`).
- Publishing a prebuilt image to a registry.

## Decisions

### Packer's native `session_manager` interface, not a tunnel on the host

The tool already has host-side SSM port forwards (`SsmSshRoute`), but Packer runs in a container.
A forward bound to the host's loopback is reachable from the container in different ways under
Docker Desktop, Podman, and Linux Docker, and the instance ID it needs is known only after Packer
launches the instance. Packer's own `session_manager` interface has neither problem: Packer
launches the instance, starts the session, and dials it, all inside the container.

### A derived image, built locally from a packaged Dockerfile

A Dockerfile resource extends the Packer image, pinned by tag and digest
(`hashicorp/packer:full-1.16.1@sha256:...`), with the plugin. It downloads a pinned version of
AWS's official Linux `.deb` (`1.2.835.0`), checks it against a pinned SHA-256 for its
architecture with `sha256sum -c`, unpacks it with `ar` and `tar` (Alpine has no `dpkg`), and installs the
binary. `gcompat` is added because the plugin is built against glibc. The final `RUN` executes
`session-manager-plugin --version`, so an image whose plugin cannot run fails at build time, not
mid-AMI-build. The architecture is chosen with `uname -m` rather than `TARGETARCH`, because the
classic builder API that docker-java and Podman use does not reliably set build arguments.

The image is tagged `easy-db-lab/packer-ssm:<first 12 hex of SHA-256 of the Dockerfile>`. If that
tag exists locally it is reused. A changed Dockerfile produces a new tag, and so a rebuild. Because
the base and the plugin are pinned, every rebuild of one Dockerfile gets the same bytes; upgrading
either means editing the Dockerfile (tag and digest, or version and both checksums), which also
changes the image tag.

Building locally keeps the tool working from a Homebrew install: the Dockerfile ships in the jar,
and nothing depends on a source checkout or a registry the tool would have to publish to.

### Templates take an optional `ssh_interface`

Both templates gain `variable "ssh_interface" { default = "" }`, and the source sets
`ssh_interface = var.ssh_interface != "" ? var.ssh_interface : null`. In HCL2, `null` leaves an
argument unset, so `direct` builds keep Packer's own default. Under `ssm`, `Packer` passes
`-var ssh_interface=session_manager` and runs in the derived image.

### Image selection is one small class

`PackerImage` decides which image Packer runs in for a transport, and makes sure that image is
present (pull for `direct`, build-if-missing for `ssm`). It is a Koin factory in `dockerModule`,
given the caller's `Docker`, and `Packer` injects it like its other collaborators. `Packer` asks
it for an image tag and otherwise runs the container exactly as before.

## Risks / Trade-offs

- **The first `ssm` build needs outbound access** to the Alpine mirror and to
  `s3.amazonaws.com`, from the container engine. A TLS-inspecting proxy that the container engine
  does not trust breaks the image build. The same proxy would also break `docker pull` and
  Packer's own AWS calls, so this adds no new failure mode, but the error appears at a new step.
- **Plugin output on musl:** if the glibc-built plugin does not run under `gcompat`, the image
  build fails on its `--version` check, before any AWS resource is created.
- **Pinned versions age:** the derived image stays on the pinned Packer and plugin versions until
  someone edits the Dockerfile. That is the price of reproducible bytes; upgrading is a one-file
  change.
