## 1. kit.yaml

- [ ] 1.1 Identity: name, type (db|app), description, version, collision-check
- [ ] 1.2 Runtime block: type (helm|pods), release/chart or selector/namespace
- [ ] 1.3 Metrics block: scrape type, port, path (if non-default)
- [ ] 1.4 Endpoints list: name, node-type, port, type (http|jdbc|native) per endpoint
- [ ] 1.5 Args list: flag, variable, description, default, type per arg
- [ ] 1.6 Capabilities list: sql with user and driver-class if applicable
- [ ] 1.7 Hooks: post-workload-start and post-workload-stop if needed
- [ ] 1.8 Install steps: helm-repo and helm entries
- [ ] 1.9 Start steps: platform-pvs if needed, manifest templates, shell wait loops
- [ ] 1.10 Stop steps: delete resources in reverse order
- [ ] 1.11 Uninstall steps: delete CRDs, helm-uninstall, platform-pvs-delete if used

## 2. K8s Templates

- [ ] 2.1 values.yaml.template parameterised with kit args as env vars
- [ ] 2.2 Additional manifest templates as needed (operator CRDs, nodeport services, etc.)

## 3. Metrics

- [ ] 3.1 Confirm metrics scrape port/path in kit.yaml
- [ ] 3.2 Write metrics-catalog.json (all exported metric names with descriptions)
- [ ] 3.3 Write METRICS.md (human-readable metrics reference)

## 4. Dashboards

- [ ] 4.1 Create dashboards/ directory with Grafana dashboard JSON
- [ ] 4.2 Register dashboard in GrafanaDashboard enum (Kotlin code change — requires compile + test)
- [ ] 4.3 Push dashboard to a running cluster and verify it loads

## 5. Integrations

- [ ] 5.1 Investigate: does this workload have a Presto JDBC connector?
- [ ] 5.2 If Presto connector exists: add connector jar to Presto kit and create catalog template
- [ ] 5.3 Investigate: does this workload support ClickHouse federated queries?
- [ ] 5.4 Investigate: any other relevant query engine connectors (Spark JDBC, etc.)?
- [ ] 5.5 Document supported integrations in README.md.template

## 6. Documentation

- [ ] 6.1 Write README.md.template (overview, args reference, endpoints, known integrations)
