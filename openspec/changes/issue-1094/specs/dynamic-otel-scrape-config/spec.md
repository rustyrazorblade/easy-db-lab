## MODIFIED Requirements

### Requirement: Install command reads metrics registry before calling OtelManifestBuilder
The install command SHALL list all ConfigMaps with label `easydblab.com/workload-metrics=true` in the cluster namespace via the Fabric8 K8s client, extract their `job-name`, `port`, and `path` data fields, and pass the resulting list to `OtelManifestBuilder`.

#### Scenario: Registry ConfigMaps discovered by label selector
- **WHEN** two ConfigMaps with label `easydblab.com/workload-metrics=true` exist
- **THEN** both are included in the scrape config list passed to `OtelManifestBuilder`

#### Scenario: ConfigMaps without the workload-metrics label are ignored
- **WHEN** a ConfigMap named `easydblab-metrics-foo` exists without the label
- **THEN** it is NOT included in the scrape config list


#### Scenario: Registry read in the cluster namespace
- **WHEN** the cluster namespace is `lab-a` and a ConfigMap with label `easydblab.com/workload-metrics=true` exists in `lab-a`
- **THEN** it is included in the scrape config list, and no ConfigMap in `default` is read
