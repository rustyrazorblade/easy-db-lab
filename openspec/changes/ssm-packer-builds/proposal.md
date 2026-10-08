## Why

`ssm-ssh-transport` carries every cluster SSH connection over SSM Session Manager when the
profile's SSH transport is `ssm`. AMI builds are still left out. `profile setup`, `build-image`,
`build-base` and `build-cassandra` run Packer, and Packer SSHes from the operator's machine to a
temporary builder instance's public IP on port 22. On a network that blocks or re-routes outbound
port 22, those builds time out exactly as cluster SSH did, so an operator on such a network cannot
produce the AMIs a cluster needs.

## What Changes

- With the `ssm` transport, Packer connects to the builder instance with its native
  `ssh_interface = "session_manager"`. Packer still uses SSH, but over a Session Manager port
  forward instead of the public IP.
- Packer runs in a container, and the stock `hashicorp/packer:full` image has no Session Manager
  plugin. Under `ssm` the tool builds a derived image (a digest-pinned Packer image plus a pinned,
  checksum-verified plugin) from a Dockerfile packaged with the distribution. It builds it locally
  on first use and reuses it while the Dockerfile is unchanged.
- With the `direct` transport, AMI builds are unchanged: the stock image, dialing the public IP.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ami-building`: AMI builds honor the profile's SSH transport.

## Impact

- Packer templates (`packer/base/base.pkr.hcl`, `packer/cassandra/cassandra.pkr.hcl`): a new
  `ssh_interface` variable, which is unset by default.
- `Packer`: picks the container image and passes `ssh_interface` by transport.
- `Docker` / `DockerClientInterface`: gain an image build.
- New packaged resource: the derived Packer image's Dockerfile.
- Builder instances already run as `EasyDBLabEC2Role`, which carries the Session Manager inline
  policy (`ssm-ssh-transport`); the IAM setup check every build runs first puts it on a role that
  lacks it. The operator policy already grants `ssm:StartSession` on
  `AWS-StartPortForwardingSession`, and on instances tagged `easy_cass_lab=1`, which the builders
  are through the templates' `run_tags`. No further IAM change is needed.
- The first `ssm` AMI build needs network access, from wherever the container engine runs, to the
  Alpine package mirror and to AWS's Session Manager plugin download.
- Docs: setup guide and network connectivity guide.
