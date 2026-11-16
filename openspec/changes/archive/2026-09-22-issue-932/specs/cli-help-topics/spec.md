## ADDED Requirements

### Requirement: Print a topic guide by name

The CLI SHALL provide a top-level `help` command that accepts an optional single topic argument. WHEN given a topic name that resolves to a discovered topic, the command SHALL print that topic's markdown body verbatim to stdout and exit with status 0.

#### Scenario: Known topic prints its body

- **WHEN** a user runs `help provisioning` (or `kits`, `stress-testing`, or `cassandra`)
- **THEN** the topic's markdown body is printed to stdout
- **AND** the command exits with status 0

#### Scenario: Topic matching is case-insensitive

- **WHEN** a user runs `help Provisioning`
- **THEN** the output is identical to `help provisioning`
- **AND** the command exits with status 0

### Requirement: List topics when no topic is given

WHEN the `help` command is run with no topic argument, it SHALL print a short explanation of the `help <topic>` usage, followed by every discovered topic with its one-line description, and exit with status 0.

#### Scenario: No-argument help lists all topics

- **WHEN** a user runs `help` with no topic argument
- **THEN** the output explains the `help <topic>` usage
- **AND** the output lists every discovered topic together with its description
- **AND** the command exits with status 0

### Requirement: Reject an unknown topic with a clean error

WHEN the `help` command is given a topic that does not resolve to any discovered topic, it SHALL print an error that names the invalid topic and lists the valid topics, then exit with a non-zero status. The error SHALL be plain user-facing text with no Java exception-class prefix.

#### Scenario: Unknown topic names the bad topic and lists valid ones

- **WHEN** a user runs `help nonsense`
- **THEN** the error output names `nonsense` as an invalid topic
- **AND** the error output lists the valid topics
- **AND** the error text carries no exception-class prefix
- **AND** the command exits with a non-zero status

### Requirement: Load topic content from packaged classpath resources

Topic content SHALL be loaded from markdown resources packaged in the distribution and discovered by scanning the classpath. The command SHALL NOT read any path inside the source repository tree, so that it works identically from a Homebrew install with no source checkout.

#### Scenario: Content resolves from a distribution with no source tree

- **WHEN** the tool is installed from a distribution with no source checkout present
- **AND** a user requests any topic
- **THEN** the topic content is loaded from a packaged classpath resource
- **AND** no path inside a source repository tree is read

### Requirement: Discover topics from packaged files without code changes

Topics SHALL be discovered from the packaged markdown files, not from a hardcoded list in code. Each topic file SHALL carry a YAML frontmatter header with a `name` field (the topic key) and a `description` field (the one-line summary). Adding a new topic SHALL require only adding a markdown file with a valid header; no code change SHALL be required.

#### Scenario: A new topic file is discovered with no code change

- **WHEN** a new `.md` file with a valid frontmatter header (`name` and `description`) is added to the packaged topic resource directory
- **THEN** it appears in the no-topic listing with its description
- **AND** it is retrievable by its key
- **AND** no code change was required for either

### Requirement: Skip a malformed topic file without failing others

WHEN a packaged topic file has a missing or malformed frontmatter header, the command SHALL skip that single file, log the condition, and continue. The remaining well-formed topics SHALL still list and resolve. A single unreadable or malformed file SHALL NOT prevent discovery of the other topics.

#### Scenario: A malformed file is skipped and the rest still work

- **WHEN** one packaged `.md` file has a missing or malformed frontmatter header
- **AND** other packaged `.md` files have valid headers
- **THEN** the malformed file is skipped and the condition is logged
- **AND** the command does not crash
- **AND** the remaining topics still appear in the listing and resolve by key

### Requirement: Seed topics are task-oriented

The distribution SHALL ship nine seed topics — `provisioning`, `kits`, `stress-testing`, `profiles`, `connecting`, `querying`, `observability`, `spark`, and `cassandra` — and each SHALL be written as task-oriented guidance describing how to perform the operation, not as a reference listing of command-line flags. The `cassandra` topic SHALL be an umbrella guide to managing the database on a running cluster — lifecycle (start, stop, restart), version selection/installation, and configuration (the Cassandra config patch-file workflow) — and SHALL point to the `stress-testing` topic for load rather than duplicating it.

#### Scenario: Each seed topic describes how to perform its operation

- **WHEN** a user reads any of the nine seed topics
- **THEN** the content explains how to carry out that operation step by step
- **AND** the content is not merely a list of command-line flags

### Requirement: Standard help output points to the topic system

The CLI's standard PicoCLI `-h`/`--help` output SHALL direct users to the `help` topic system. The root usage SHALL carry a footer telling the user to run `help` for task guides. Each subcommand that maps to a topic SHALL carry a footer naming the related `help <topic>`. The pointer text SHALL be derived from the discovered topic set, not from a second hardcoded list that could drift from the packaged topics.

#### Scenario: Root usage footer points to the help topics

- **WHEN** a user runs `easy-db-lab -h`
- **THEN** the usage output includes a footer directing the user to run `help` for task-oriented topic guides

#### Scenario: A command's usage points to its related topic

- **WHEN** a user runs `-h` on a subcommand that maps to a topic
- **THEN** the usage output includes a footer naming the related `help <topic>`

#### Scenario: Pointer text tracks the discovered topics

- **WHEN** the pointer footers are produced
- **THEN** the topic names they reference come from the discovered topic set, not a separately hardcoded list
