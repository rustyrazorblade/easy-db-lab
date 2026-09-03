## Context

`init` configures two symmetric node groups — database and application (stress) — but
its flags for them are asymmetric and unmemorable, and it takes an explicit CPU
architecture flag that duplicates a property EC2 already reports for the chosen instance
type. Meanwhile the `DescribeInstanceTypes` call that would supply that architecture is
broken for any instance type newer than the installed SDK (#783). This change namespaces
the node-group options, removes the architecture flag in favor of derivation, fixes and
consolidates the instance-type query, and carries a per-group architecture all the way
through provisioning so mixed-architecture clusters work.

The design draws directly on an existing pattern in the same command. `Init.kt` already
exposes a dotted flag family for EBS — `--ebs.type`, `--ebs.size`, `--ebs.iops`,
`--ebs.throughput`, `--ebs.optimized` — implemented as individual `@Option`s whose names
happen to contain a dot. There is no picocli `@ArgGroup`; the dot is purely a naming
convention that signals "these belong to the EBS family." The db/app namespacing follows
that precedent exactly.

## Goals / Non-Goals

**Goals**

- Give the db and app node groups a consistent, learnable option scheme (`--db.*` /
  `--app.*`) without breaking any existing flag.
- Derive CPU architecture from the instance type; remove the redundant, error-prone
  `--arch` flag.
- Fix #783: decouple instance-type support from the SDK's `InstanceType` enum, and do it
  once — one `DescribeInstanceTypes` call yielding both instance-store presence and
  supported architectures.
- Carry a per-group architecture through to provisioning so a mixed-architecture cluster
  is provisioned with the right AMI per group.
- Fail fast, loudly, at `init` when architecture cannot be derived.

**Non-Goals**

- The `--jdk-distro` / Corretto work and any packer / JDK-install / Cassandra-version
  changes (#796).
- Migrating persisted state. Clusters are ephemeral; new `InitConfig` fields carry
  defaults so an old `state.json` still deserializes, and that is sufficient.
- Reintroducing an architecture override. Derivation is the only path.
- Changing the `build-image` / packer architecture path, which legitimately builds an
  AMI for an explicitly chosen architecture.

## Decisions

### D1: Namespaced options as individual `@Option`s, not an `@ArgGroup`

`init` gains `--db.instance-type`, `--app.instance-type`, `--db.count`, `--app.count`,
each a nullable field with no default (`String?` / `Int?`). Each legacy flag keeps its
current default and becomes an alias for the same concept:

| Concept            | Legacy flag (keeps default)                 | Namespaced (nullable) |
| ------------------ | ------------------------------------------- | --------------------- |
| db instance type   | `--instance` / `-i` (env default)           | `--db.instance-type`  |
| app instance type  | `--stress-instance` / `-si` / `--si` (env)  | `--app.instance-type` |
| db count           | `--db` / `--cassandra` / `-c` (default 3)   | `--db.count`          |
| app count          | `--app` / `--stress` / `-s` (default 0)     | `--app.count`         |

This mirrors the existing `--ebs.*` family, which is a set of plain `@Option`s with dots
in their names and no `@ArgGroup`. A picocli `@ArgGroup` was considered and rejected: it
would change how the legacy aliases coexist with the namespaced names, buys nothing here
(the family is flat, with no mutual-exclusivity or co-occurrence rules to enforce), and
diverges from the `--ebs.*` precedent the user already knows.

*Trade-off:* two option names now map to one concept, so `--help` lists both. That is the
cost of not breaking muscle memory and scripts, and it is the same cost the codebase
already pays for `-i` vs `--instance`.

### D2: Namespaced-wins precedence, implemented order-independently

The resolved value for each concept is computed as `resolved = namespaced ?: legacy`.
Because the namespaced field is null unless the user set it, and the legacy field always
holds either the user's value or the default, this rule is:

- **order-independent** — picocli assigns each field regardless of argument order, and
  the resolution reads fields, not parse order; and
- **namespaced-wins** — a non-null namespaced value always shadows the legacy field.

`Init` exposes resolved accessors (e.g. `resolvedDbInstanceType`, `resolvedAppCount`),
and `InitConfig.fromInit` reads those accessors rather than the raw fields. `--help` text
states that the namespaced form takes precedence.

*Alternative considered — last-flag-wins:* rejected. It would make behavior depend on CLI
argument order, which is surprising and hard to script against, and picocli does not
expose per-option parse order without custom handling. `namespaced ?: legacy` is simpler
and deterministic.

### D3: One filter-based `DescribeInstanceTypes` call (closes #783)

`EC2InstanceService` gains `describeInstanceType(type: String)` returning a small value
holding `hasInstanceStore: Boolean` and `supportedArchitectures: List<String>`. It issues
a single `DescribeInstanceTypes` request built with an `instance-type` **filter** whose
value is the raw instance-type string:

```
DescribeInstanceTypesRequest.builder()
    .filters(Filter.builder().name("instance-type").values(type).build())
    .build()
```

An empty result means the instance type is unknown in the region and fails fast (D6). The
call is wrapped in `RetryUtil` (resilience4j) as the existing AWS calls are; exhaustion
propagates (D6). `hasInstanceStore()` is reimplemented as a thin reader that calls
`describeInstanceType(type).hasInstanceStore`.

The current code routes the instance type through `InstanceType.fromValue(instanceType)`,
which returns `null` for any type absent from the installed SDK enum and produces the
`[null]` error in #783. That path is removed for user-supplied instance types. The
RunInstances path (`createSingleInstance`) is also moved off `InstanceType.fromValue` onto
the string-accepting `instanceType(String)` overload so provisioning matches init-time
validation and no longer couples to the SDK enum.

*Why consolidate rather than add a second call:* architecture and instance-store presence
come from the same API response for the same instance type. Two calls would double the
API traffic and leave two places to keep enum-decoupled. One call, one reader each.

### D4: `Arch.fromEc2(...)` mapping

`Arch` gains a `fromEc2(architectures: List<String>): Arch` (or a per-string
`fromEc2(String)`), mapping EC2's `SupportedArchitectures` values to the enum:
`x86_64` → `AMD64`, `arm64` → `ARM64`. `i386` and any unrecognized or empty value are
rejected. When a type reports multiple mappable architectures (rare, but possible), that
is ambiguous for AMI selection and is rejected naming the type (D6) rather than guessed.
The `Arch` enum and `PicoArchConverter` remain in the tree — `PicoArchConverter` still
serves the `build-image` path, and `Arch` is now also the type of the derived, persisted
per-group value.

### D5: Per-group architecture in state, per-architecture AMI at `up`

`InitConfig.arch: String` (a single global architecture) is replaced by three fields —
`dbArch`, `appArch`, `controlArch` — each a `String` with a default so older `state.json`
files still deserialize (no migration; clusters are ephemeral). `Init.execute` derives
each group's architecture from that group's resolved instance type and stores all three.

`InstanceSpec` gains the resolved AMI for its group (an `amiId`, or the `arch` from which
`up` resolves the AMI). At `up`, `buildInstanceConfig` resolves one AMI per **distinct**
architecture across the groups (via the existing `AMIResolver.resolveAmiId`), and attaches
the matching AMI to each `InstanceSpec`. `createInstancesForType` reads `spec.amiId`
instead of a single global AMI, and the global `amiId` field is dropped from
`InstanceProvisioningConfig` (and the intermediate infra-context that carries it).

This is the SOLID fix: architecture stops being a cluster-wide singleton that silently
misapplies one AMI to heterogeneous nodes, and becomes a property owned by each node
group. A cluster with an `arm64` db group and an `x86_64` app group provisions each group
from its own AMI.

*Trade-off:* `up` may now resolve more than one AMI (one per distinct architecture). For
the common single-architecture cluster this is exactly one resolution, unchanged in cost;
resolutions are deduplicated by architecture so a three-group same-arch cluster still
resolves once.

### D6: Fail fast — never silently default architecture

Per the repository rule never to disable functionality or paper over a configuration
problem:

- **Unknown instance type** — `describeInstanceType` returns an empty result → error at
  `init`: the instance type is not found in the region. No instances are created.
- **API unavailable / throttled** — after `RetryUtil` (resilience4j) exhausts, the failure
  propagates; `init` does not fall back to a default architecture.
- **Zero or multiple mappable architectures** — `Arch.fromEc2` cannot pick a single
  architecture → error naming the instance type. Never guessed.

All three surface at `init`, before any EC2 instance is launched, consistent with the
existing instance-storage validation which also fails fast at init.

### D7: Persistence — Jackson defaults, no migration

`InitConfig` is persisted through the (deprecated but still in use) Jackson path with
lenient defaults. The new per-group architecture fields (`dbArch`, `appArch`,
`controlArch`) are added with defaults, so an older `state.json` written before this
change still deserializes. No migration step is needed because clusters are ephemeral —
there is no long-lived state to carry forward. New serialization work continues to prefer
kotlinx.serialization per project convention, but this change does not convert
`InitConfig`; it only adds defaulted fields to the existing structure.

## Risks / Trade-offs

- **Two names per concept in `--help`.** Accepted; it is the price of not breaking
  existing flags, and matches the `-i`/`--instance` status quo.
- **Multiple AMI resolutions at `up`.** Bounded by the number of distinct architectures
  (at most three), deduplicated, and unchanged for the common single-arch case.
- **Removing `--arch` is a CLI break.** Intentional. The flag only ever restated the
  instance type's architecture and was a source of misconfiguration. Clusters are
  ephemeral; there is no compatibility surface to preserve.

## Migration

None. New `InitConfig` fields carry defaults; older `state.json` files load unchanged.
The removed `--arch`/`-a`/`--cpu` flag has no replacement — architecture is derived.
