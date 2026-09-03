# Test and CI policy — easy-db-lab

This repo owns this file. spec-flow reads it and ships no default; if it goes away, the
pipeline stops rather than falling back to anything. Every line here is ours to change,
including the local/CI split itself. Keep it short — every implementation and review
agent reads it on every run.

## The suite is two tiers

- **Unit tier** — `src/test/kotlin`, run by `./gradlew test`. No Docker, no sockets, no
  real I/O.
- **Integration tier** — `src/integrationTest/kotlin`, run by `./gradlew integrationTest`.
  TestContainers, the Fabric8 kubernetes-server-mock, okhttp MockWebServer, fixed-port
  sockets.

The split is enforced by the compiler, not by convention. `testcontainers`,
`fabric8 kubernetes-server-mock` and `okhttp-mockwebserver` are scoped to
`integrationTestImplementation` only. A test that needs one of them does not compile
under `src/test/`. Put it in the integration tier.

## Local gate — runs on every TDD cycle

```bash
./gradlew test
```

The fast tier only. This runs on every red-green-refactor cycle, so it must stay fast.
Do not put the integration tier here.

## Local gate — before pushing a branch

```bash
./gradlew ktlintFormat
./gradlew check
```

`check` runs both test tiers plus `detekt` and `ktlintCheck`. **Run it on JDK 21.**
detekt 1.23.8 cannot run under a JDK 25 runtime. Compiling, running and testing the app
work on 25; `check` and `detekt` do not.

The integration tier needs a running Docker daemon. If TestContainers fails to start,
that is a broken local Docker, not a broken test. Say so and stop. Never record a
TestContainers failure as pre-existing or environment-specific.

## CI — `.github/workflows/pr-checks.yml`

CI is a real test gate. Two jobs run in parallel on every pull request and every push to
`main`:

- `test` — `./gradlew test integrationTest koverXmlReport` on JDK 21. GitHub's ubuntu
  runners ship a running Docker daemon, so the integration tier runs for real.
- `quality` — `./gradlew ktlintCheck` then `./gradlew detekt` on JDK 21.

Kover reports coverage to the PR with an 80% floor, overall and on changed files.

On any test failure the `test` job writes every failing test id, one
`com.example.FooTest.method` per line across both tiers, and uploads it as the
`spec-flow-failures` artifact. `/spec-flow:sync-ci` pulls that into the branch's flagged
set. This contract is already wired. Do not break it when editing the workflow.

## Merge gate

Green CI is necessary and not sufficient. **GitHub does not enforce it.** `main` has
branch protection, but its required-status-check list is empty, so nothing blocks a merge
mechanically. The gate is ours to hold:

1. Both CI jobs are green.
2. The owner has tested the change, on a real cluster where the change touches cluster
   behavior.
3. The owner squash-merges.

**Never use `gh pr merge --auto`.** It has merged a PR here before CI finished. Poll the
checks and merge only on green.

An agent never merges a feature PR on its own unless the issue carries the
`merge-on-green` label, and even then only after every required check reports green.

## Push cadence

Push the issue branch and open a PR. Never push to `main`. CI runs both tiers on the PR,
so results are ready by the time a human looks.
