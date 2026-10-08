## Overrides existing behavior

### kit-command-args: Kit command args declaration
**Currently:** a kit declares named CLI options per command under `commands:` using `KitArgSpec`. Scenarios: command args appear in help; a given value reaches the script as an env var; an omitted optional arg injects its declared default; a runtime arg overrides an install-time resolved arg. Nothing is said about an omitted optional arg with no default (the code records the string `"null"`), booleans, or how install and command args relate.
**This change:** install args and command args are built by one shared arg-option builder; an omitted optional arg with no default is not recorded (its variable is absent, never `"null"`); an omitted boolean is `false`, a given one `true`. Adds the scenarios "Unset optional arg with no default is not recorded", "Unset boolean arg is false", "Given boolean arg is true" and "Install args and command args behave the same". The four existing scenarios are unchanged.

### kit-metrics-declaration: One ConfigMap written per scrape target after `start` completes
**Currently:** after a successful `start`, one ConfigMap per scrape entry named `easydblab-metrics-<job>`, labelled `easydblab.com/workload-metrics: "true"` and `easydblab.com/kit: <kitName>`, with `job-name`, `port`, `path`. Scenarios name `easydblab-metrics-<kitName>` (single target) and `easydblab-metrics-<job>` (multi target), and no ConfigMap after a failed start. A failed registration is not specified (the code logs a warning and `start` succeeds).
**This change:** the name is `easydblab-metrics-<kitName>-<job>` (matching `MetricsRegistryService`; the single-target scenario becomes `easydblab-metrics-<kitName>-<kitName>`). A failed registration emits a typed event naming the kit and the failure and `start` exits non-zero, for every kit. Adds the scenario "Failed metrics registration fails start".

### typed-install-steps: kit.yaml declares kit version and dashboard files
**Currently:** `kit.yaml` supports `version` and a `dashboards` list; dashboards are installed after a successful `start`; they are skipped after a failed `start`. A dashboard that cannot be read, is missing, or is rejected by Grafana is not specified (the code logs a warning or emits `KitDashboardsSkipped` and `start` succeeds).
**This change:** any kit dashboard install failure — unreadable or unrenderable dashboards or tenant listing, a missing declared file, or a Grafana rejection — emits a typed event naming the kit, the dashboard and the reason, and `start` exits non-zero, for every kit. Adds the scenarios "Grafana rejects a dashboard" and "Dashboards cannot be read". The two existing scenarios are unchanged.

No requirement is REMOVED. The `ami-building`, `stress-testing` and `containerized-sidecar` deltas are ADDED requirements only; no existing spec text mentions ECR pull secrets, so the removal of `EcrPullSecretService` overrides code behavior, not spec text.

## Conflicts with other in-flight changes

- **issue-966** (`cloudwatch-metrics-export`, `cluster-lifecycle`, `grafana-annotations`, `observability-store`): touches none of this change's capabilities. No conflict.
- **issue-970** (`account-compactor`, `cluster-lifecycle`, `observability-store`): touches none of this change's capabilities. No conflict.
- **issue-971** (`cassandra`, `cloudwatch-metrics-export`, `cluster-comparison-dashboard`, `grafana-install-dashboard`, `multi-cluster-dashboards`, `observability`, `observability-store`, `server`, `spark-emr`, `test-documents`, `tests-dashboard`, `tool-execution`): touches none of this change's capabilities. No conflict.
- **issue-992** (`ami-building`, `cassandra`): shares `ami-building`. It ADDS the requirement "Base AMI JDKs"; this change ADDS "Kubelet ECR credential provider on every node". Different requirements, neither modifies "AMI Creation" or "AMI Maintenance". Compatible; both rebake the base AMI, which is independent work.
- **ssm-packer-builds** (`ami-building`): shares `ami-building`. It ADDS the requirement "AMI builds honor the SSH transport"; this change ADDS "Kubelet ECR credential provider on every node". Different requirements; neither modifies the other. Compatible. Its `packer/base/base.pkr.hcl` edit (an `ssh_interface` variable) is next to, not on, the provisioner list this change adds a script to.
- **ssm-ssh-transport** (`networking`, `setup`): touches none of this change's capabilities. No conflict.
