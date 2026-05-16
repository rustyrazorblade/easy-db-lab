## MODIFIED Requirements

### Requirement: install.yaml Descriptor
Each workload directory SHALL contain an `install.yaml` descriptor. In addition to the existing
`name`, `description`, `collisionCheck`, and `args` fields, `install.yaml` SHALL support:
- `version` (string, optional): the workload version being installed; shown in `--list` output
- `dashboards` (list, optional): dashboard files to install after a successful `start` phase
- `install`, `start`, `stop`, `uninstall` (list, optional): typed step sequences for each
  lifecycle phase

```yaml
name: clickhouse
version: "24.3"
description: "Install ClickHouse via the Altinity operator"
collisionCheck: true
dashboards:
  - path: dashboards/clickhouse-overview.json
    name: ClickHouse Overview
args:
  - flag: --replicas
    variable: REPLICAS
    description: "ClickHouse replica count"
    default: "${DB_NODE_COUNT}"
install:
  - type: helm-repo
    name: altinity
    url: https://charts.altinity.com
  - type: helm
    chart: altinity/altinity-clickhouse-operator
    release: clickhouse-operator
    namespace: kube-system
start:
  - type: manifest
    template: clickhouseinstallation.yaml.template
  - type: wait
    kind: ClickHouseInstallation
    name: "${CLUSTER_NAME}-clickhouse"
    condition: Completed
    timeout: 300s
stop:
  - type: delete
    kind: ClickHouseInstallation
    name: "${CLUSTER_NAME}-clickhouse"
uninstall:
  - type: helm-uninstall
    release: clickhouse-operator
    namespace: kube-system
```

#### Scenario: Workload with typed steps is registered as a subcommand
- **WHEN** `install.yaml` contains typed lifecycle phases
- **THEN** `easy-db-lab install <workload>` registers successfully and `--list` shows the workload
  with its `version` field

#### Scenario: Version shown in list output
- **WHEN** `install.yaml` contains `version: "24.3"`
- **THEN** `easy-db-lab install --list` displays the version alongside the workload name

## REMOVED Requirements

### Requirement: Shell script templates in bin/ are mandatory
**Reason**: Replaced by typed step sequences. Shell script templates are now optional; typed steps
are the preferred mechanism.
**Migration**: Workloads with existing `bin/start.sh.template` and `bin/stop.sh.template` continue
to work via the script fallback path. Migrate to typed steps by adding lifecycle phases to
`install.yaml` and removing the template files.
