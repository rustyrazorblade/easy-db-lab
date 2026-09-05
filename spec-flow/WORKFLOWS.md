# Review panel policy — easy-db-lab

This repo owns this file. spec-flow reads it and ships no default; if it goes away, the
pipeline stops rather than falling back to anything. Every line here is ours to change,
including whether a panel runs at all. Keep it short — every implementation and review
agent reads it on every run.

## The panel

Five lenses run concurrently on the branch diff after the developer agent's first green
commit. Each earns its slot for a reason specific to this repo:

- **`reviewer`** — spec conformance plus this repo's own conventions. The conventions are
  unusually load-bearing here: typed `Event.Domain.*` versus `println()`, no
  `Event.Message`/`Event.Error`, fabric8 builders over YAML strings, kotlinx.serialization
  over Jackson, resilience4j over hand-rolled retry loops, constants in `Constants`. All
  are written in `CLAUDE.md`, and none are caught by a compiler.

- **`code-reviewer`** — correctness only. Logic errors, boundary mistakes, unhandled error
  paths, `!!` on nullable values, concurrency faults, resource leaks.

- **`security-reviewer`** — self-gating; returns empty on a diff with no security surface.
  It earns its slot because this repo holds AWS credentials, opens SSH sessions, and runs
  a SOCKS proxy whose global-property misuse caused a real incident. Never set the
  `socksProxyHost` / `socksProxyPort` JVM properties; see `Socks5ProxySelector` and
  the `SOCKS Proxy` requirement in the networking spec.

- **`test-rigor-reviewer`** — this repo bans mock-echo tests outright: a test that only
  verifies a mock was called with the values it was handed proves nothing. It also bans
  mocking `TemplateService`, and bans mocking `K8sService` or `RemoteOperationsService`
  to test manifest application. This lens is what catches those.

- **`observability-reviewer`** — the product is an observability lab. New code paths and
  failure modes must be diagnosable, and events must carry structured fields rather than
  prose.

## What must be fixed

Blocking. The fix loop runs until these are gone:

- Any correctness finding.
- Any security finding the security lens did not self-gate away.
- Any convention finding that contradicts `CLAUDE.md` or a package-level `CLAUDE.md`.
- Any missing test on a code path that makes a decision, transforms data, or can fail.

Advisory. Recorded in the PR, not blocking:

- Style preferences with no rule behind them.
- Over-built test findings, unless the test churn is large enough to slow the suite.

## Never fixed by weakening the check

A finding is never resolved by disabling functionality, making a dependency optional,
skipping a test, adding a memory limiter to an OTel collector, or lowering a threshold.
If a check cannot pass, that is a real problem. Surface it to the owner and stop.

Backwards compatibility is never a finding. Clusters here are ephemeral; there is no
migration path to preserve. Large `storageSize` defaults are never a finding either; the
local-storage provisioner ignores PVC capacity.

## Round cap

Three fix rounds. If blocking findings remain after the third, stop and hand the issue to
the owner with what is left. Do not loop further.
