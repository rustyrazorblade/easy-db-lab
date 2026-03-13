## 1. GitHub Actions Workflow

- [ ] 1.1 Create `.github/workflows/publish-dashboards.yml` that triggers on `v*` tags, zips all JSON files from `src/main/resources/com/rustyrazorblade/easydblab/configuration/grafana/dashboards/`, and uploads `easy-db-lab-dashboards.zip` as a release asset using `softprops/action-gh-release@v2`

## 2. Documentation

- [ ] 2.1 Add a section to `docs/` (or the Grafana dashboard reference page) explaining the published artifact, the download URL pattern, and the requirement to substitute `__KEY__` template variables before importing dashboards into Grafana
