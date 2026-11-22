# Test plan, real AWS: teardown module (#1242)

This file continues [`test-plan.md`](test-plan.md).

Each run uses its own workspace under `clusters/`, and passes `--tag embark_offhours=off` to `init`.  `easy-db-lab` runs on its default profile, which already uses the `easy-db-lab` AWS profile; no `AWS_PROFILE` or `EASY_DB_LAB_PROFILE` is set.  Every `aws` command takes `--profile easy-db-lab`.  Names below are placeholders; the PR records the real output with every id masked and no account numbers.

Masking, applied to every saved output before it is compared or posted:

```bash
sed -E 's/(vpc|subnet|sg|i|igw|rtb|nat|eni|rtbassoc)-[0-9a-f]+/<\1>/g'
```

## L1, a late subnet (R1)

It runs once on `main`, as the control, and once on the branch.

1. `easy-db-lab init l1 --up --tag embark_offhours=off`.
2. `mkfifo answer`, then `exec 3<>answer`, so the fifo has a writer before `down` starts and `down` never reads an early end of file.
3. `easy-db-lab down <answer >down.log 2>&1 &`.
4. Wait until `down.log` shows the confirmation prompt.
5. Read the network: `jq -r .vpcId state.json`, and its block with `aws ec2 describe-vpcs --vpc-ids <vpc> --query 'Vpcs[0].CidrBlock' --profile easy-db-lab`.
6. Create the late subnet in an unused /24 of the primary block.  `up` uses only the first three of the eight /19 subnets, so the first /24 of the eighth /19 is free: for `10.0.0.0/16` that is `10.0.224.0/24`.  Run `aws ec2 create-subnet --vpc-id <vpc> --cidr-block 10.0.224.0/24 --profile easy-db-lab`, and record its id.
7. Answer the prompt: `echo yes >&3`, then `exec 3>&-`, and wait for `down` to exit.

| | on main (control) | on the branch |
|---|---|---|
| exit code | 0 | non-zero |
| `aws ec2 describe-subnets --subnet-ids <late> --profile easy-db-lab` | NotFound (deleted, never shown) | the subnet exists |
| `jq .infrastructureStatus state.json` | `"DOWN"` | `"UP"`, `vpcId` kept |
| a second `down --auto-approve` | (not needed) | lists the late subnet, removes it and the VPC, exits 0 |

## L2, same resources removed (R7)

It runs on `main` and on the branch, for an own VPC and for a supplied VPC.

- **Supplied VPC setup:**
  - `aws ec2 create-vpc --cidr-block 10.77.0.0/16 --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=l2-supplied}]' --profile easy-db-lab`;
  - `aws ec2 create-subnet --vpc-id <id> --cidr-block 10.77.255.0/24 --profile easy-db-lab`, a pre-existing user resource;
  - then `easy-db-lab init l2s --vpc <id> --up --tag embark_offhours=off`.  `up` splits the VPC's own block into eight /19 subnets and uses the first one per zone (three by default), so the user's subnet in the eighth /19 does not collide.
- **Before `down --auto-approve`**, record the network into `before.json`:
  - `describe-instances`, `describe-subnets`, `describe-security-groups` and `describe-route-tables`, each with `--filters Name=vpc-id,Values=<vpc>`;
  - `describe-internet-gateways --filters Name=attachment.vpc-id,Values=<vpc>`.
- **After `down`:**
  - The own-VPC run: every recorded resource and the VPC are gone.  An instance counts as gone when its state is `terminated`.
  - The supplied-VPC run: every resource tagged with the cluster's `ClusterId` is gone, and the VPC, the `10.77.255.0/24` subnet and the main route table remain.
- **Compare main and branch**, both masked:
  - the kind and count of resources removed;
  - phase 1 (the instance, EMR and OpenSearch lines, which run in parallel) as a sorted set;
  - the lines from the end of phase 1 to the end of `down`, in order, with the process id dropped.

  They must match.  The discovery lines print once on the branch and twice on main; that is the expected difference.
- **Cleanup:**
  - `aws ec2 delete-subnet` for the `10.77.255.0/24` subnet;
  - `aws ec2 delete-vpc` for the supplied VPC;
  - `describe-vpcs` with the `Name` filter returns nothing.
