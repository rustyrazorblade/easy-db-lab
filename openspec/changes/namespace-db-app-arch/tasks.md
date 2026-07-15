# Tasks

Ordered by dependency. Practice TDD throughout: write the failing test first. Use AssertJ,
extend `BaseKoinTest`, use `@TempDir` for temporary directories. No mock-echo tests — every
test must exercise a decision, transformation, or real failure mode. Never mock
`TemplateService`. Add class-level KDoc; no wildcard imports; use `RetryUtil` (resilience4j)
for AWS retries.

## 1. Consolidated instance-type query (closes #783)

- [x] 1.1 Write a failing test: an instance type not present in the SDK's `InstanceType` enum
  is resolved successfully — no `[null]` error — for both instance-store detection and
  architecture, because the query passes the type as an `instance-type` filter string.
- [x] 1.2 Add `EC2InstanceService.describeInstanceType(type)` returning a value with
  `hasInstanceStore` and `supportedArchitectures`, built from a single filter-based
  `DescribeInstanceTypes` call wrapped in `RetryUtil`. An empty result throws naming the
  type and region.
- [x] 1.3 Reimplement `hasInstanceStore()` as a thin reader over `describeInstanceType`.
- [x] 1.4 Move the RunInstances path (`createSingleInstance`) off `InstanceType.fromValue`
  onto the string-accepting `instanceType(String)` overload; do not reintroduce
  `InstanceType.fromValue` for user-supplied types anywhere.

## 2. Architecture mapping helper

- [x] 2.1 Write a failing test for `Arch.fromEc2`: `x86_64` → AMD64, `arm64` → ARM64;
  `i386`, empty, unrecognized, and multiple-mappable inputs each throw naming the cause.
- [x] 2.2 Add `Arch.fromEc2(...)` implementing that mapping.

## 3. Namespaced options and precedence

- [x] 3.1 Write a failing test: when both a namespaced option and its legacy alias are set,
  the resolved value is the namespaced one, independent of argument order; when only the
  legacy flag (or neither) is set, the legacy value / default is used.
- [x] 3.2 Add nullable, no-default `@Option`s `--db.instance-type`, `--app.instance-type`,
  `--db.count`, `--app.count` on `Init`, following the `--ebs.*` individual-`@Option`
  precedent (no `@ArgGroup`). Keep every legacy flag as an alias with its current default.
- [x] 3.3 Add resolved accessors on `Init` (`resolved = namespaced ?: legacy`) and state the
  namespaced-wins precedence in each option's `--help` description.
- [x] 3.4 `InitConfig.fromInit` reads the resolved accessors, not the raw legacy fields.

## 4. Architecture derivation at init; remove `--arch`

- [x] 4.1 Write a failing test: `init` derives each group's architecture from its resolved
  instance type via `describeInstanceType`, and an unknown instance type fails at `init`
  before any provisioning, naming the type and region.
- [x] 4.2 Derive `dbArch` / `appArch` / `controlArch` in `Init.execute` and persist them.
- [x] 4.3 Replace `InitConfig.arch: String` with defaulted `dbArch` / `appArch` /
  `controlArch`; confirm an older `state.json` (with the old single `arch`) still loads.
- [x] 4.4 Remove the `--arch` / `-a` / `--cpu` option from `Init` (keep `Arch` and
  `PicoArchConverter` for the `build-image` path).

## 5. Per-architecture AMIs through provisioning

- [x] 5.1 Write a failing test: a mixed-architecture cluster (db `arm64`, app `x86_64`)
  provisions each group from the AMI matching its own architecture; distinct architectures
  are resolved once each and attached per group.
- [x] 5.2 Add the resolved AMI (or arch) to `InstanceSpec`; resolve one AMI per distinct
  architecture in `Up.buildInstanceConfig` and attach it per spec.
- [x] 5.3 Have `createInstancesForType` read `spec.amiId`; drop the global `amiId` from
  `InstanceProvisioningConfig` and its infra-context.

## 6. Documentation

- [x] 6.1 Update the `init` reference in `docs/` for `--db.*` / `--app.*`, the legacy
  aliases and precedence, and the removal of `--arch`.
- [x] 6.2 Update `CLAUDE.md` flag notes where they describe init options or architecture.
