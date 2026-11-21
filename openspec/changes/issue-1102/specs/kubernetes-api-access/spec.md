## ADDED Requirements

### Requirement: A missing exec auth plugin fails fast, naming the plugin

When the kubeconfig is loaded and the selected context's user authenticates through an exec auth plugin, the system SHALL check that the plugin's command can be found before any client is built. A bare command name SHALL be looked up on the process `PATH`; a command that contains a path separator SHALL be resolved against the kubeconfig file's directory when relative. When the command is not found, the load SHALL fail with a typed exception that names the plugin, the kubeconfig user, the context and the `PATH` it was looked up on, and the Kubernetes provider error handler SHALL report it as `Event.Command.ProviderError` kind `credentials`, whose remedy is the kubeconfig's `installHint` when it has one and otherwise to install the plugin or put it on `PATH`. A user with no exec plugin, or whose plugin is found, is unaffected.

The check exists because Fabric8 does not fail on a missing plugin: it logs a warning and sends the request with no credentials, so the user would otherwise see an unexplained 401 or an anonymous 403.

#### Scenario: Exec plugin not on PATH

- **GIVEN** a kubeconfig whose context's user runs the exec plugin `aws` (or `gke-gcloud-auth-plugin`, or `kubelogin`), and a `PATH` that does not hold it
- **WHEN** any command loads that kubeconfig
- **THEN** it fails before any API request, and the output names the plugin `aws`, the kubeconfig user, the context and the `PATH` searched
- **AND** the exit code is non-zero

#### Scenario: Install hint shown

- **GIVEN** the same kubeconfig with an `installHint` on the exec entry
- **WHEN** the load fails
- **THEN** the remedy is that install hint

#### Scenario: Relative plugin path

- **GIVEN** an exec command `./bin/token-helper` relative to the kubeconfig's directory
- **WHEN** the file exists there, the load succeeds; **WHEN** it does not, the failure names the resolved path

#### Scenario: Plugin present, or no plugin

- **GIVEN** a user whose exec plugin is on `PATH`, or a user with a client certificate or token and no exec entry
- **WHEN** the kubeconfig loads
- **THEN** no check failure occurs
