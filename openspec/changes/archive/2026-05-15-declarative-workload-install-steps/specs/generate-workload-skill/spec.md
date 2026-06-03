## ADDED Requirements

### Requirement: A Claude skill generates new workload configs interactively
The repository SHALL include a Claude skill at `.claude/skills/generate-workload/` that, given a
workload name, researches the appropriate installation mechanism, proposes a complete `install.yaml`
with typed steps, refines the proposal interactively with the user, and writes the output files.

#### Scenario: Skill generates install.yaml from workload name
- **WHEN** a user invokes the skill with a workload name (e.g., "ScyllaDB")
- **THEN** the skill researches the helm chart, CRDs, readiness conditions, and recommended
  configuration
- **AND** proposes a draft `install.yaml` with typed lifecycle steps before writing any files

#### Scenario: Skill refines proposal interactively
- **WHEN** the user provides feedback on the draft (e.g., "add TLS option", "default to 3 nodes")
- **THEN** the skill updates the proposal and re-presents it for confirmation
- **AND** does not write output files until the user explicitly approves

### Requirement: Skill output includes all files needed for a working workload
Upon user approval, the skill SHALL write:
- `src/main/resources/com/rustyrazorblade/easydblab/commands/install/<name>/install.yaml`
- Any `.template` files referenced by `manifest` steps
- A checklist item reminding the user to add a `--<workload>` validation step to
  `bin/end-to-end-test` for real-cluster validation

#### Scenario: Template files generated for manifest steps
- **WHEN** the approved `install.yaml` contains a `manifest` step referencing
  `templates/cr.yaml.template`
- **THEN** the skill generates a starter `cr.yaml.template` populated with known field values
  from the workload's CRD schema

#### Scenario: Skill emits E2E validation checklist
- **WHEN** the skill writes output files
- **THEN** it prints a checklist item: "Add `--<workload>` flag to `bin/end-to-end-test` and
  validate with a live cluster before merging"

### Requirement: Generated install.yaml is valid and parseable
The skill SHALL produce `install.yaml` content that passes the CLI's own parser without error.

#### Scenario: Generated file parses without errors
- **WHEN** the skill writes `install.yaml`
- **THEN** running `easy-db-lab install --list` includes the new workload without parse errors
