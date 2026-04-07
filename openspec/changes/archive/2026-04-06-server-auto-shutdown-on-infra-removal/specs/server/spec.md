## ADDED Requirements

### Requirement: Auto-shutdown CLI option
The server command SHALL accept an `--auto-shutdown` flag that enables infrastructure watchdog behavior.

#### Scenario: Flag not provided
- **WHEN** the user starts the server without `--auto-shutdown`
- **THEN** no watchdog is started and the server runs indefinitely

#### Scenario: Flag provided
- **WHEN** the user starts the server with `--auto-shutdown`
- **THEN** the infrastructure watchdog service is started as a background service

