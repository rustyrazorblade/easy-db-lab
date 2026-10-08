## Why

Every interaction easy-db-lab has with cluster nodes travels over SSH to the node's public IP on
port 22: `up`'s readiness wait, all remote operations, the SOCKS tunnel, Tailscale bootstrap, and
the `env.sh` aliases. On networks that route egress through a corporate egress security proxy
performing source-IP anchoring, port-22 connections to freshly provisioned, never
pre-registered public IPs time out, so `up` cannot complete and the tool has no working
connection method at all.

AWS Systems Manager Session Manager reaches an instance through an outbound connection the SSM
agent makes to AWS, so it needs no inbound port and no knowledge of the operator's source IP.
Carrying SSH over Session Manager removes the hard dependency on inbound port-22 reachability
without changing anything else about how the tool works.

## What Changes

- A per-profile **SSH transport** setting: `direct` (default, today's behaviour) or `ssm`.
  `profile setup` offers it; `profile show` displays it.
- With `ssm`:
  - The generated `sshConfig` routes each host through an SSM session (`ProxyCommand`), so the
    SOCKS tunnel and every `env.sh` alias work unchanged.
  - The in-process SSH client reaches each node through a local SSM port-forwarding session.
  - `up` verifies the AWS CLI and the Session Manager plugin are installed locally before it
    creates any AWS resource.
- The cluster instance role (`EasyDBLabEC2Role`) always carries a minimal inline Session Manager
  policy (`SessionManagerInstance`: `ssm:UpdateInstanceInformation` and the four `ssmmessages`
  channel actions), in place of the managed `AmazonSSMManagedInstanceCore`, which would also grant
  Parameter Store reads on every parameter. It is put on the role when the role is created, on
  every `up`, and by every command that runs the IAM setup check (AMI builds included), so existing
  profiles pick it up without re-running setup.
- The operator IAM policy shown by `show-iam-policies` includes the SSM session permissions:
  sessions only to instances tagged `easy_cass_lab=1`, and only the operator's own sessions to end
  or resume.
- Packer AMI builds over SSM are covered by the separate `ssm-packer-builds` change.

## Capabilities

### New Capabilities

None — the change extends existing capabilities.

### Modified Capabilities

- `networking`: SSH connections may be carried over SSM Session Manager, selected per profile.
- `setup`: the instance role carries SSM permissions; the operator policy includes SSM session
  permissions; profile setup offers the SSH transport.

## Impact

- User profile (`settings.yaml`): new `sshTransport` field, default `direct`.
- Generated `sshConfig`: per-host `ProxyCommand` when `ssm`.
- In-process SSH client: endpoint resolution becomes transport-aware; SSM port-forward processes
  are started per instance and torn down at JVM exit.
- `up`: local tooling preflight when `ssm`; instance role SSM policy re-asserted.
- IAM: inline Session Manager policy on `EasyDBLabEC2Role`; SSM statements in the EC2 user policy.
- New local prerequisites when `ssm`: AWS CLI v2 and `session-manager-plugin`.
- Docs: network connectivity guide, setup guide.
