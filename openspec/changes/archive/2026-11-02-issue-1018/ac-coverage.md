| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | `ingress allow` with no value adds the detected `/32`; output names CIDR and security group | `networking: Allow with no value adds the detected public IP` | ✅ Covered |
| AC | `--cidr 203.0.113.7` adds `203.0.113.7/32` | `networking: A bare IP becomes a /32` | ✅ Covered |
| AC | `--cidr 198.51.100.0/24` adds exactly that CIDR | `networking: A CIDR is used exactly` | ✅ Covered |
| AC | Repeated `--cidr` adds a rule per value and reports each | `networking: Repeated values each get a rule`; `networking: Duplicate values collapse after normalizing` | ✅ Covered |
| AC | Allow of an existing rule succeeds, changes nothing, reports already exists | `networking: Allowing an existing rule changes nothing` | ✅ Covered |
| AC | Revoke with no value removes the detected `/32` and names it | `networking: Revoke with no value removes the detected public IP` | ✅ Covered |
| AC | Revoke removes only the matching rule; other SSH rules, VPC rules and egress unchanged | `networking: Revoke removes only the matching rule` | ✅ Covered |
| AC | Revoke of a missing rule exits non-zero naming it; matches still removed | `networking: Revoke of a missing rule fails and names it` | ✅ Covered |
| AC | Invalid value fails before any AWS call, names it, changes nothing | `networking: An invalid value stops the command before any AWS call`; `networking: Malformed values are rejected` | ✅ Covered |
| AC | Outside a workspace or no security group in state: clear failure, no AWS call | `networking: No cluster security group in state` | ✅ Covered |
| AC | Detection fails with no value: message says detection failed and `--cidr` can be given | `networking: Public IP detection fails`; `networking: A stalled detection server fails in bounded time` | ✅ Covered |
| AC | Revoking own current IP works with no guard | `networking: Revoking the operator's own IP is allowed` | ✅ Covered |
| AC | `--open` cluster: allow adds, revoke `0.0.0.0/0` removes | `networking: An open cluster's world rule is a normal CIDR` | ✅ Covered |
| AC | Each outcome is a domain-specific typed event | `networking: Allow with no value adds the detected public IP`; `networking: Revoke of a missing rule fails and names it`; `networking: An invalid value stops the command before any AWS call`; `networking: No cluster security group in state`; `networking: The security group no longer exists in AWS`; `networking: Public IP detection fails`; `networking: An AWS error on one CIDR does not stop the others` | ✅ Covered |
| Owner | `init --cidr` strict rules: explicit prefix, host bits rejected, `/20` kept | `networking: init --cidr rejects a bare address with a clear message`; `networking: init --cidr rejects host bits` | ✅ Covered |
| Owner | Host bits rejected naming the network form (D4) | `networking: Host bits set are rejected with the network form` | ✅ Covered |
| Owner | ipify call has bounded timeouts | `networking: A stalled detection server fails in bounded time` | ✅ Covered |
| Owner | Hint names `ingress allow` | `networking: The hint names the fix` | ✅ Covered |
| Owner | `EC2VpcService` security-group code split into `EC2SecurityGroupService` | — | ⚠️ Excluded — structural move with no observable behavior; covered by task 2.1 and the existing tests passing unchanged |
| Risk | Commands must not need or start the tunnel (would fail in the changed-IP case) | `networking: Ingress commands do not start the tunnel` | ✅ Covered |
| Risk | Security group deleted outside the CLI | `networking: The security group no longer exists in AWS` | ✅ Covered |
| Risk | Rules-per-group quota or other AWS error part-way | `networking: An AWS error on one CIDR does not stop the others` | ✅ Covered |
| Risk | Moving the event out of the provider changes `up` output or double-prints for `ingress allow` | `networking: up reports each added rule once`; `networking: ingress allow prints one line per CIDR` | ✅ Covered |
| Risk | A tunnel can outlive a revoke (stateful security groups) | — | ⚠️ Excluded — AWS behavior, not system behavior; noted in design and the real-cluster check expects it |
| Risk | EC2 client uses the profile region, not the cluster state region | `networking: The security group no longer exists in AWS` | ✅ Covered |
| Risk | `ssm` transport: port 22 unused | — | ⚠️ Excluded — the command behaves the same on both transports; no transport-specific behavior to specify |
