# Workflow

This file states how work moves from a GitHub issue to `main`.  It replaces the old spec-flow files.  The owner set every rule here; change a rule only when the owner says so.

## Roles

- **Owner:** sets priorities, puts work in the queue, and approves feature specs.  The owner does not review code.
- **Lead:** the session that manages the queue and delegates issues to agents.  It does not write the code for an issue.
- **Issue agent:** one agent per issue.  It implements, tests, reviews, opens the PR, and merges.

## State files

State that changes often lives in state files, not in Hindsight.  Hindsight holds long-term memory only: decisions, rationale, conventions and durable findings.

- The lead keeps `$CLAUDE_JOB_DIR/tmp/state/lead.md`.  It holds the active work, the work queue, and every delegated agent with its name, issue, worktree, branch and status.
- Each issue agent keeps `$CLAUDE_JOB_DIR/tmp/state/agents/<agent-name>.md`, in the lead's job directory.  It holds the worktree and branch, the current step, decisions and why, the AMI ID, each cluster name and workspace, test results and blockers.
- An agent updates its file after each significant step.  After a context compaction, it reads the file first.

## Status reports

When the owner asks for status, the lead answers with two bullet lists and nothing more:

- **Now:** one bullet per active issue: the issue, the agent, and its current step.
- **Queue:** one bullet per queued item, in priority order.
- **Closed since last status:** one bullet per issue closed since the previous status report: the number, the title in a few words, and how it closed (merged PR, already done, or not planned).

The lead reads the agent state files before it answers.

The lead reports at once, without a status request, when an agent finishes, merges, is blocked, or has a question for the owner.

## The work queue

- The lead manages the queue in `lead.md`.  The queue holds the work that the owner put in it.
- At most three issues are in progress at the same time.  Other items wait.
- If the queue is empty and a slot is free, the lead proposes the highest-priority `status:ready` issue that has no `agent:active` or `blocked` label.  It waits for the owner's yes before it starts the issue.

## Review trains

To improve throughput, batch small changes into one review train.

- A train is one agent, one worktree, one branch, and one PR for several small issues.
- Each issue gets its own commit, with `Closes #<n>` in the commit message.
- The five review lenses, the CI checks and the merge run once for the whole train.
- A train merges with `gh pr merge --rebase`, not `--squash`, so each issue keeps its own commit on `main`.
- A train counts as one of the three slots.
- Put a feature, or any issue that needs its own spec approval, in its own PR.
- The lead orders the commits so that an issue that touches the same files as an open PR comes last, and the train rebases on `main` before it starts those commits.

## Issue labels

- **Lifecycle**, one at a time, in this order:
  - `status:ready`: groomed and ready to start.
  - `status:spec-review`: a feature's OpenSpec change passed its plan panel and waits for owner approval.
  - `status:in-progress`: implementation is in progress.
  - `status:in-review`: the PR is open and goes through review and CI.
  - `status:addressing`: the agent fixes review or CI findings.
- **Priority:** `P0` (drop everything), `P1` (high), `P2` (normal), `P3` (low / someday).
- **Control:**
  - `agent:active`: an agent works on the issue now.  Do not start a second one.
  - `blocked`: a hard dependency stops the issue.  Do not start it.
- **Gates:**
  - `requires-aws`: the change must pass a real AWS test before merge.
  - `requires-ami-bake`: the change touches `packer/` or something else baked into the AMI.

The lead sets `requires-aws` and `requires-ami-bake` before it delegates.  It determines them from the issue's scope and the tree.  A later agent never decides either one.

## Agents and models

- **Plans:** an Opus agent writes the plan for each feature (see "Planning new work").  It owns the design decisions of that work.
- **Builds of a plan:** a Sonnet agent builds the approved plan when the work is mechanical.  A hard problem (concurrency, a design gap found during the build, a hard bug) may go to Opus.  A step that the Sonnet agent fails twice goes back to Opus.  This is a default, not a hard rule.
- **Kotlin work:** `dev-skills:kotlin-dev`, in its own worktree.
- **Small work** (bug fixes, chores, small changes): a Sonnet agent.
- **Builds and tests:** a Sonnet `build-engineer` with a narrow brief: run the given Gradle tasks and return only the result and the failures.  The issue agent never runs a Gradle build in its own context.
- **Load, stress and performance tests:** the `easy-db-lab-tester` agent, never inline.
- **Other work:** the default model.

The lead states the chosen agent and model in `lead.md`.

## Worktrees

- **Features and all other delegated work:** every subagent works in its own git worktree.  The lead spawns it with `isolation: "worktree"`.  It never edits the main checkout.
- **Small bug fixes and docs:** these may be done in the main checkout, but only after the owner approves that for the specific change.  Without that approval, use a worktree.
- An approved main-checkout change may be committed and pushed straight to `main` when the owner says so.  Otherwise it uses a branch and a PR.
- The lead records each agent's worktree path and branch in `lead.md`.

## Planning new work

The owner set this process on 2026-11-21 for all new work.  Work already in progress finishes in its own way.

A **feature** is a new capability or a behavior change.  Bug fixes, chores, test-only work, docs and pure refactors are not features; they go straight to code, and spec edits go in the same PR.

1. **Issue.**  Each piece of work has an issue.  In prose, write `#NNN (description)`, never a bare number.
2. **Plan.**  An Opus plan agent writes the OpenSpec change under `openspec/changes/<name>/` and validates it with `openspec validate`.  Before any code, the change holds three parts:
   - **The architecture plan** (`design.md`): the design, the alternatives, and how the design keeps provider types behind provider-neutral ports.
   - **The test plan**: for each requirement, its tests by name and tier (unit, integration, or real AWS).  Each test has a control that is seen to fail: break the check on purpose, see the test fail, then revert.  The plan states what runs in CI and what runs on a real cluster.
   - **The task list** (`tasks.md`): each task is marked mechanical or judgement.
3. **Pre-work plan panel.**  Four reviewers read the three parts, at the same time, before the first build task:
   - **Adversarial:** tries to break the design: failure modes, races, wrong assumptions, partial failures of AWS or SSH calls, and concurrent commands in one workspace.
   - **Test gaps:** finds requirements and failure paths with no test, tests that cannot fail, missing controls, timing-based tests, and mock-echo tests.
   - **Tech debt:** finds coupling, duplication, new provider coupling, complexity, and work that the next step will throw away.
   - **Performance and scale:** checks the design at the largest clusters the tool runs (about 100 nodes), and checks `up` and `down` time, AWS API call counts and rate limits, SSH fan-out, and observability volume and cardinality.  It flags any cost that grows with the node count in a loop that runs one node at a time.
4. **Refinement.**  The panel writes one HTML report, linked from the change.  The planner revises the plan, and the same four reviewers review it again, up to 3 rounds.  Each round's report keeps the earlier findings and states how each one was closed.  Stop early when a round has no CRITICAL or MAJOR finding.  Never waive a performance or scale finding to end the loop.  If a CRITICAL or MAJOR finding is still open after round 3, it goes to the owner.
5. **Zoom out.**  If a review finds a MAJOR error in something just revised, stop patching and re-examine the approach.  Put "refactor the abstraction above this" next to "patch this spot", and compare them on risk, size and how many future bugs each removes.  If a plan file passes 300 lines, split the change.
6. **Real-cluster proof.**  A change that targets a measured limit (provisioning time, throughput, a scale limit) names a validation plan in the spec before code: the limit with today's baseline, the workload, the expected effect, and the pass criterion.  Run the same workload on the same cluster shape before and after, and put the numbers in the PR.
7. **Owner approval.**  After the panel closes, the agent renders the change as one self-contained HTML file and opens it with `open <file>`.  Use `dev-skills:ide-explain` or `dev-skills:walkthrough` to render it.  The HTML links the panel report.  The agent sets `status:spec-review` and stops.  The lead tells the owner at once.  Code starts only after the owner approves.
8. **The brief.**  Each build step gets a brief in `$CLAUDE_JOB_DIR/tmp/state/briefs/<name>.md`: the worktree and git rules, the sources, the edit points as `file:line`, the tests and controls by name, and the hand-back.  A brief has no context-size stop.
9. **Build.**  A Sonnet agent builds the mechanical tasks; judgement tasks stay on Opus (see "Agents and models").  The review, CI and merge rules below still apply.

Specs are the source of truth.  If a change conflicts with an existing spec, stop and tell the owner.

## Tests

The suite has two tiers.

- **Unit tier:** `src/test/kotlin`, run by `./gradlew test`.  No Docker, no sockets, no real I/O.
- **Integration tier:** `src/integrationTest/kotlin`, run by `./gradlew integrationTest`.  TestContainers, the Fabric8 kubernetes-server-mock, okhttp MockWebServer, and fixed-port sockets.

The compiler enforces the split.  `testcontainers`, `fabric8 kubernetes-server-mock` and `okhttp-mockwebserver` are in `integrationTestImplementation` only, so a test that needs one of them does not compile under `src/test/`.

### Local gates

- **Each TDD cycle:** `./gradlew test`.  Keep the integration tier out of this gate.
- **Before a push:** `./gradlew ktlintFormat`, then `./gradlew check`.  `check` runs both tiers, `detekt` and `ktlintCheck`.  Run it on JDK 21, because detekt 1.23.8 cannot run on JDK 25.

The integration tier needs a running Docker daemon.  If TestContainers does not start, local Docker is broken.  Say so and stop.  Never record a TestContainers failure as pre-existing or environment-specific.

### Real AWS tests (`requires-aws`)

A mocked AWS client accepts a wrong parameter, a wrong region, or a missing permission.  Static review cannot prove an AWS call works.

- The issue agent starts its own cluster, runs the test, and tears the cluster down.  It does not ask the owner first.
- It uses the `easy-db-lab` profile and adds the tag `embark_offhours=off`.
- It records the cluster name and workspace in its state file when the cluster starts.
- It never leaves a cluster idle.
- The PR holds the evidence: the commands, their output, and the AWS-side result.  "Verified manually" is not evidence.

### AMI bakes (`requires-ami-bake`)

- With the label, the issue agent bakes the AMI by itself and tests with it.  It records the AMI ID in its state file.
- Run `./gradlew installDist` before `build-image`.  `build-image` bakes from `build/install/easy-db-lab/packer/`, so a bake without `installDist` ships the old copy and reports success.
- Without the label, never bake.  Test against the existing AMI.

## Review

After the first green build, five review lenses run at the same time on the branch diff.  Each lens is a separate agent with a narrow brief.

- **Conventions and spec:** the diff matches the issue, the specs, and the rules in `CLAUDE.md` and the package `CLAUDE.md` files.  Examples: typed `Event.Domain.*` events versus `println()`, no `Event.Message` or `Event.Error`, fabric8 builders instead of YAML strings, kotlinx.serialization instead of Jackson, resilience4j instead of retry loops, constants in `Constants`.
- **Correctness:** logic errors, boundary mistakes, unhandled error paths, `!!` on nullable values, concurrency faults, resource leaks.
- **Security:** returns nothing when the diff has no security surface.  This repo holds AWS credentials, opens SSH sessions, and runs a SOCKS proxy.  Never set the `socksProxyHost` or `socksProxyPort` JVM properties.
- **Test rigor:** no mock-echo tests, no mocked `TemplateService`, and no mocked `K8sService` or `RemoteOperationsService` to test manifest application.
- **Observability:** new code paths and failure modes are diagnosable, and events carry structured fields, not prose.

### Blocking findings

The agent fixes these before merge:

- Any correctness finding.
- Any security finding.
- Any convention finding that contradicts a `CLAUDE.md`.
- Any missing test on a code path that makes a decision, transforms data, or can fail.

Style preferences with no rule behind them are advisory.  The agent records them in the PR.

### Fix rounds

There is no fixed round cap.  After two fix rounds, zoom out before the next round:

- Is the problem more general than the finding?
- Is the code too narrow for the real problem?
- Does the test test the wrong thing?

Fix the general problem, not the next narrow symptom.

### Never weaken a check

Never resolve a finding by any of these actions:

- Disable functionality, or make a dependency optional.
- Skip a test.
- Add a memory limiter to an OTel collector.
- Lower a threshold, or add a detekt suppression or baseline entry.

If a check cannot pass, tell the owner and stop.

Backwards compatibility is never a finding; clusters are ephemeral.  Large `storageSize` defaults are never a finding; the local-storage provisioner ignores PVC capacity.

## CI

`.github/workflows/pr-checks.yml` runs two jobs on every pull request and every push to `main`:

- `test`: `./gradlew test integrationTest testScripts koverXmlReport` on JDK 21.  `testScripts` runs every Docker-free shell-script test.
- `quality`: `./gradlew ktlintCheck`, then `./gradlew detekt`, on JDK 21.

Path-filtered workflows run the Docker-backed script tests:

- `.github/workflows/packer-test.yml`, on `packer/**` changes: `testPackerBase`, `testPackerCassandra` and `testAxonSudoers`.
- `.github/workflows/fluent-bit-filter-test.yml`, on changes to `fluent-bit-severity-mapper*.lua`: `testFluentBitFilter`.

Kover reports coverage on the PR, with an 80% floor overall and on changed files.

### Docs-only PRs

A PR that changes only the paths in the `paths-ignore` list of `pr-checks.yml` gets no `test` or `quality` run.  The list holds `openspec/**` and the root process docs (`WORKFLOW.md`, `README.md`, `CONTRIBUTING.md`, `DEVELOPERS.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `history.md`).  Nothing in the build or the tests reads these paths.

- `docs/`, `test-plans/`, `.claude/` and the root `CLAUDE.md` are not docs-only: unit tests read them.
- Before you add a path to the list, check that no test or build task reads it.
- If a PR changes `openspec/`, run `openspec validate --all` before the merge, and put the result in the PR.
- Pushes to `main` always run the full suite.

If a test fails, the `test` job uploads the `spec-flow-failures` artifact.  It lists each failing test ID, one per line, across both tiers.  Script tests appear as `scripts.<task>`; run one with `./gradlew <task>`.  Use it to find the failing tests with `gh run download`.

## Merge

Every issue agent's change reaches `main` through a pull request.

Open each PR as a draft.  Mark it ready only after a real-cluster QA run with the easy-db-lab plugin (`/easy-db-lab:plan`, then `/easy-db-lab:run`), and after a PR comment that says what was tested: the cluster shape, the commands, the expected and seen results, and what was not tested.  A docs-only PR needs no cluster run.  `CONTRIBUTING.md` holds the same rule for outside contributors.  The only direct pushes are main-checkout changes that the owner approved for a direct push (see "Worktrees").

A ruleset named `main` lists the `test`, `quality` and `claude-review` checks, but its enforcement is disabled, so GitHub does not enforce them.  The agent holds the gate itself.  Read the ruleset with `gh api repos/OWNER/REPO/rulesets/<id>`; the classic branch-protection endpoint is misleading.

The issue agent squash-merges its own PR when all of these are true:

1. The five review lenses have no blocking findings.
2. If the issue has `requires-aws`, the real AWS test passed and the PR holds the evidence.
3. `test`, `quality` and `claude-review` are green.  A docs-only PR (see "Docs-only PRs") has no `test` or `quality` run; it needs `claude-review` green and roborev passed.

Rules for the merge:

- Never use `gh pr merge --auto`.  It merged a PR here before CI finished.  Poll the checks and merge only on green.
- An agent merges only the PR it opened.  It never pushes to, updates, or merges another agent's PR.
- After the merge, the agent closes the issue, removes `agent:active`, and returns its report.  The lead tells the owner at once.
