## Context

Currently, `setup_instance.sh` scans for an unpartitioned NVMe device (`nvme0n1`, `nvme1n1`, `xvdb`) to use as the data disk. When the instance type has no instance store and no EBS volume is attached, the script silently falls through — creating `/mnt/db1` on the root partition. This leads to a broken cluster (16GB root disk fills up).

The `Init` command defaults `--ebs.type` to `"NONE"`. There is no check at init time to verify the chosen instance type has local storage. The AWS `DescribeInstanceTypes` API provides instance store information in the `instanceStorageInfo` field.

## Goals / Non-Goals

**Goals:**
- Fail fast at init time when a Cassandra instance type has no local storage and no EBS is configured.
- Use the AWS `DescribeInstanceTypes` API to determine if the instance type has instance store.
- Provide a clear error message telling the user to either pick an instance type with instance store or specify `--ebs.type`.

**Non-Goals:**
- Auto-selecting EBS parameters when instance store is missing (user should decide).
- Validating stress or control node instance types (they don't need data disks).
- Caching instance type metadata across runs.

## Decisions

### 1. Where to validate: `InstanceSpecFactory` during spec creation

**Rationale:** The `InstanceSpecFactory.createInstanceSpecs()` method already assembles the instance specs including EBS config. This is the natural place to check "does this instance type have storage?" before returning specs. The factory already has all the info needed (instance type + EBS config). The factory will need access to an EC2 client to call `DescribeInstanceTypes`.

**Alternative considered:** Validate in the `Init` command directly. Rejected because validation logic belongs in the service layer, not the command.

### 2. Use `DescribeInstanceTypes` API

**Rationale:** This is the canonical AWS API for querying instance type capabilities. The `instanceStorageSupported` boolean field tells us exactly what we need. No need for a hardcoded list of instance families.

**Alternative considered:** Pattern-matching on instance type name (e.g., looking for "d" suffix like `c5d`). Rejected because it's fragile — instance families don't follow perfectly consistent naming.

### 3. Fail with an exception, not a warning

**Rationale:** The project follows fail-fast principles. A cluster without a data disk is broken. Warnings would let users create broken clusters.

## Risks / Trade-offs

- **Additional API call:** `DescribeInstanceTypes` adds one API call to the init flow. → Low risk; it's a fast, cacheable metadata query.
- **API permissions:** The caller needs `ec2:DescribeInstanceTypes`. → This is a read-only API that's almost always permitted; standard EC2 usage policies include it.
