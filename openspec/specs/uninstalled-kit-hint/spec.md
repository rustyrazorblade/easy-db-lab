# Uninstalled Kit Hint

## Purpose

When the user types a top-level subcommand that is not installed but matches an available
kit template, the CLI SHALL print an actionable error message instead of the generic
PicoCLI unmatched-argument error.

## Requirements

### Requirement: Hint for uninstalled available kit

When an unrecognized top-level argument exactly matches the name of an available (installable) kit template, the CLI SHALL print a targeted message identifying the kit as not installed and providing the exact command to install it.

#### Scenario: User types an available but uninstalled kit name

- **WHEN** the user runs `easy-db-lab presto` and `presto` is not installed
- **AND** `presto` is a known installable kit template
- **THEN** the CLI SHALL print: `presto is not installed. Run: easy-db-lab install presto`
- **AND** the process SHALL exit with code 2

#### Scenario: Unknown command falls through to default error

- **WHEN** the user runs `easy-db-lab unknownxyz`
- **AND** `unknownxyz` does not match any available kit template
- **THEN** the CLI SHALL display PicoCLI's default unmatched-argument error with suggestions
- **AND** the process SHALL exit with code 2

#### Scenario: Hint is case-sensitive

- **WHEN** the user runs `easy-db-lab Presto`
- **AND** the available template name is `presto` (lowercase)
- **THEN** the CLI SHALL NOT print the "not installed" hint
- **AND** the CLI SHALL display PicoCLI's default error output
