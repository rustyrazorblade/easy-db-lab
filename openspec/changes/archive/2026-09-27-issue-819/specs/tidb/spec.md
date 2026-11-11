## MODIFIED Requirements

### Requirement: TiDB Kit Exposes Prometheus Metrics

The TiDB kit SHALL configure metrics scraping by pod discovery: the TiDB SQL layer on container port `10080`, PD on `2379`, and TiFlash on `8234`, each selected by `app.kubernetes.io/component=<component>,app.kubernetes.io/instance=tidb`, with path `/metrics`. Only the collector on the node running each pod SHALL scrape it. The metrics NodePorts MAY remain for manual access but SHALL NOT be scrape targets.

#### Scenario: Metrics scrape job registered

- **WHEN** the TiDB kit is started
- **THEN** a pod-discovery scrape job is registered for the TiDB SQL layer on container port `10080` with path `/metrics`, AND `up` has exactly one series per TiDB SQL pod
