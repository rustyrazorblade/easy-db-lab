## ADDED Requirements

### Requirement: down deletes exactly the confirmed plan

`down` MUST discover the resources to remove once, before the preview and the confirmation prompt.  After the operator confirms (or `--auto-approve`), `down` MUST remove only the resources in that discovered plan, and MUST NOT discover again before or during the removal.  A resource that appears after the preview MUST NOT be removed by that `down`.  When such a resource blocks the removal of a planned resource (a subnet, a security group or the network itself), `down` MUST report the failure, exit non-zero and leave the cluster state as it was, so a later `down` previews the new resource before removing it.

`init --clean` and `down` MUST resolve which resources belong to the workspace's cluster through the same rule: the whole network when easy-db-lab created it, and only the cluster's own tagged resources when the network was supplied with `init --vpc`.

#### Scenario: A resource created during the tail save is not removed

- **GIVEN** a running cluster whose teardown the operator confirmed
- **WHEN** a new resource is created in the cluster's network while the tail save runs
- **THEN** `down` removes only the resources the preview listed
- **AND** the new resource is still there after `down` ends

#### Scenario: A late resource that blocks the network delete fails down

- **GIVEN** a confirmed teardown of a network easy-db-lab created, and a subnet created in it after the preview
- **WHEN** `down` removes the planned resources
- **THEN** the network delete fails, `down` reports the error and exits non-zero
- **AND** the cluster state is not marked down
- **AND** the next `down` lists the new subnet in its preview

#### Scenario: --all removes only the networks it previewed

- **GIVEN** `down --all` whose preview listed two networks
- **WHEN** a third tagged network is created before the operator confirms
- **THEN** only the two previewed networks are torn down

#### Scenario: init --clean and down agree on the cluster's resources

- **GIVEN** a workspace whose cluster runs in a network supplied with `init --vpc`
- **WHEN** the operator runs `down --dry-run`, and separately `init --clean`
- **THEN** both look only at the resources tagged with the cluster's `ClusterId`

### Requirement: Teardown of the workspace's own cluster

`down` MUST treat a teardown as including the workspace's own cluster when it is the current-cluster teardown, a `down <vpc-id>` that names the workspace's network, or a `down --all` whose preview includes the workspace's network, and never for `down --packer`.

When a confirmed teardown includes the workspace's own cluster, `down` MUST run the pre-teardown save before any infrastructure is removed, under the rules of "Cluster Teardown" (`--force` and a telemetry redirect skip it).  After a successful removal it MUST stop the SOCKS tunnel, remove the control node's Tailscale device, delete the Tailscale auth key, stop the data bucket's request metrics and mark the cluster state down.  When the removal fails, it MUST stop the SOCKS tunnel and MUST NOT change the cluster state.

When a teardown does not include the workspace's own cluster, or the operator declines the prompt, `down` MUST NOT run the save, stop the SOCKS tunnel, remove the Tailscale device, delete the auth key, stop the bucket metrics or change the cluster state.

#### Scenario: down of another VPC leaves the workspace alone

- **GIVEN** a workspace with a running cluster and a recorded Tailscale device
- **WHEN** the operator runs `down <vpc-id>` for a different VPC and confirms
- **THEN** that VPC's resources are removed
- **AND** the workspace's cluster state, Tailscale device and SOCKS tunnel are left as they were

#### Scenario: down of the workspace's VPC by id saves the tail

- **GIVEN** a running cluster
- **WHEN** the operator runs `down <vpc-id>` naming the cluster's own network and confirms
- **THEN** the pre-teardown save runs before any infrastructure is removed
- **AND** after a successful removal the cluster state is marked down

#### Scenario: down --all that includes the workspace's network saves the tail

- **GIVEN** a running cluster in this workspace, and other easy-db-lab networks in the account
- **WHEN** the operator runs `down --all` and confirms
- **THEN** the pre-teardown save runs before any network is removed

#### Scenario: A declined prompt keeps the tunnel

- **GIVEN** a running cluster whose SOCKS tunnel is up
- **WHEN** the operator declines the `down` prompt
- **THEN** the SOCKS tunnel is still running and the cluster state is unchanged
- **AND** `down` exits non-zero
