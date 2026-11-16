# Proposal: Namespace db/app init options and auto-derive architecture

## Why

`init`'s node-group options grew organically and read inconsistently. Two groups of
nodes — the database nodes and the application (stress) nodes — each have an instance
type and a count, but the flags that set them share no visible structure:
`--instance`/`-i` sets the db instance type while `--stress-instance`/`--si` sets the
app instance type; `--db`/`--cassandra`/`-c` sets the db count while
`--app`/`--stress`/`-s` sets the app count. There is no naming convention a user can
learn once and apply to both groups. The EBS options already solved this for their own
family with a dotted `--ebs.*` scheme (`--ebs.type`, `--ebs.size`, `--ebs.iops`, …).
This change extends that precedent to the db/app groups with `--db.*` / `--app.*`.

Architecture is worse than inconsistent — it is redundant. `init` takes an explicit
`--arch`/`-a`/`--cpu` flag that the user must keep in agreement with the instance type
they chose. An `arm64` instance type with the default `AMD64` arch resolves the wrong
AMI and provisions a cluster that cannot boot. The architecture is already a property
of the instance type; EC2 reports it through `DescribeInstanceTypes`
(`SupportedArchitectures`). The flag exists only to restate information AWS already
holds, and it exists only to be set wrong. This change removes it and derives the
architecture per node group from the resolved instance type at `init` time.

Deriving architecture requires one `DescribeInstanceTypes` call per distinct instance
type. That is the same call `hasInstanceStore()` already makes, and today that call is
broken. It routes the user-supplied instance type through the AWS SDK's `InstanceType`
enum via `InstanceType.fromValue(...)`, which returns `null` for any instance type not
yet present in the installed SDK version's enum — so `init -i <new-type>` fails with
`The following supplied instance types do not exist: [null]` for any instance type
newer than the SDK. This is **#783**. Rather than make a second, differently-broken
call for architecture, this change consolidates both facts (instance-store presence and
supported architectures) into a single filter-based `DescribeInstanceTypes` call that
passes the instance type as a raw string, decoupling instance-type support from the SDK
release version. This proposal therefore **closes #783**.

Refs **#773** (this issue; the JDK-distribution half was split to **#796** and is out of
scope here). Closes **#783**.

## What Changes

**Namespaced db/app options with legacy aliases.** `init` gains four new options —
`--db.instance-type`, `--app.instance-type`, `--db.count`, `--app.count` — each nullable
with no default. Every existing flag keeps working as an alias carrying its current
default: `--instance`/`-i` and `--stress-instance`/`--si` for the instance types,
`--db`/`--cassandra`/`-c` and `--app`/`--stress`/`-s` for the counts. When both a
namespaced option and its legacy alias are supplied, **the namespaced form always wins**,
independent of the order they appear on the command line. This precedence is stated in
`--help`. Following the `--ebs.*` precedent, the dot is a naming convention only — these
are individual `@Option`s, not a picocli `@ArgGroup`.

**Architecture auto-derived; `--arch` removed.** The `--arch`/`-a`/`--cpu` option is
removed entirely — there is no override. At `init`, the architecture of each node group
is derived from that group's resolved instance type via the EC2 `DescribeInstanceTypes`
`SupportedArchitectures` field: `x86_64` → AMD64, `arm64` → ARM64; `i386` or an empty
result is rejected. The derived architecture is persisted per node group in cluster
state. The separate `build-image` / packer path that builds AMIs for an explicit
architecture is unchanged, and the `Arch` enum remains as the type of the derived and
stored value.

**One consolidated `DescribeInstanceTypes` call (closes #783).** A single filter-based
call per instance type returns both whether the type has local instance store and its
supported architectures. Instance types are passed as raw strings via an `instance-type`
filter, never through the SDK's `InstanceType` enum, so any valid EC2 instance type
works regardless of the installed SDK version. The existing instance-store check becomes
a thin reader over this consolidated result.

**Per-group architecture through provisioning (mixed-arch clusters).** The single global
architecture stored in cluster state is replaced by a per-group architecture (db, app,
control). At `up`, one AMI is resolved per distinct architecture present across the
groups, and each node group is provisioned with the AMI matching its own architecture.
A cluster with an `arm64` database group and an `x86_64` application group is a valid,
correctly-provisioned configuration — today it is not, because a single global AMI is
applied to every node.

**Fail-fast at init.** Consistent with the repository rule never to disable
functionality: an unknown instance type (empty `DescribeInstanceTypes` result) fails at
`init` naming the type and region; a `DescribeInstanceTypes` call that remains
unavailable or throttled after retry propagates its failure; an instance type whose
architectures map to zero or more than one supported architecture fails naming the type.
Architecture is never silently defaulted.

## Capabilities

### Modified Capabilities

- `cluster-lifecycle`: `REQ-CL-001: Cluster Initialization` gains the namespaced
  `--db.*` / `--app.*` options, the legacy-alias compatibility guarantee, and the
  namespaced-wins precedence rule. `REQ-CL-002: Infrastructure Provisioning` gains
  per-group architecture and per-architecture AMI resolution so a mixed-architecture
  cluster is provisioned correctly. A new requirement, `Architecture derived from
  instance type`, covers init-time derivation, per-group persistence, no direct override,
  and fail-fast on an underivable architecture.
- `instance-storage-validation`: the `Instance store detection via AWS API` requirement
  is modified so instance types are resolved by an `instance-type` filter rather than
  the SDK's `InstanceType` enum (closing #783), and so the single `DescribeInstanceTypes`
  call also reports supported architectures.

### New Capabilities

None. The behavior belongs to two existing capabilities.

## Impact

**Code**

- `commands/Init.kt` — new nullable `--db.*` / `--app.*` options; legacy flags become
  aliases; `--arch`/`-a`/`--cpu` removed; resolved-value accessors applying
  namespaced-wins precedence; init-time architecture derivation.
- `configuration/InitConfig.kt` (in `ClusterState.kt`) — `arch: String` replaced by
  per-group `dbArch` / `appArch` / `controlArch`; `fromInit` reads the resolved values.
  New fields carry defaults so older persisted state still loads (clusters are ephemeral;
  no migration).
- `configuration/Arch.kt` — `Arch.fromEc2(...)` mapping helper.
- `services/aws/EC2InstanceService.kt` — `describeInstanceType(type)` returning
  instance-store presence and supported architectures via one filter-based call;
  `hasInstanceStore()` becomes a thin reader; no `InstanceType.fromValue` on user input.
- `services/aws/InstanceSpecFactory.kt` — `InstanceSpec` carries the resolved AMI (or
  architecture) for its group.
- `commands/Up.kt` — per-architecture AMI resolution in `buildInstanceConfig`; the
  global `amiId` on `InstanceProvisioningConfig` is dropped in favor of per-spec AMIs.
- `services/ClusterProvisioningService.kt` — `createInstancesForType` reads the AMI from
  the spec.

**Behavior**

- `init -i <new-instance-type>` succeeds for any valid EC2 instance type, including types
  newer than the installed SDK enum (was `[null]` failure — #783).
- `init` no longer accepts `--arch`/`-a`/`--cpu`; architecture is derived automatically.
- A mixed-architecture cluster provisions each group with the correct AMI.

**Docs**

- `docs/` init reference: document `--db.*` / `--app.*`, the legacy aliases and
  precedence, and the removal of `--arch`.
- `CLAUDE.md` flag notes as needed.

**Explicitly out of scope**

- `--jdk-distro` / Corretto and any packer / JDK-install / Cassandra-version changes
  from the old `feature/jdk-distro-app-arch` branch — **#796**.
