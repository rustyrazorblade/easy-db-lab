## 1. Create GitHub Actions Workflow

**Note:** The GitHub App used by Claude Code does not have the `workflows` scope. This file must be created manually by a human with write access to the repository.

- [x] 1.1 Create `.github/workflows/publish-dashboards.yml` with the following content:

```yaml
name: Publish Dashboards

on:
  push:
    branches:
      - main
    tags:
      - 'v*'
  workflow_dispatch:

permissions:
  contents: write

jobs:
  publish-dashboards:
    name: Package and publish Grafana dashboards
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v6

      - name: Package dashboards
        run: |
          DASHBOARDS_DIR="src/main/resources/com/rustyrazorblade/easydblab/configuration/grafana/dashboards"
          mkdir -p artifacts
          cd "$DASHBOARDS_DIR"
          zip -j "$GITHUB_WORKSPACE/artifacts/easy-db-lab-dashboards.zip" *.json

          echo "Dashboard archive contents:"
          unzip -l "$GITHUB_WORKSPACE/artifacts/easy-db-lab-dashboards.zip"

      - name: Upload dashboards to version release
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: artifacts/easy-db-lab-dashboards.zip
          fail_on_unmatched_files: true

      - name: Upload dashboards to latest pre-release
        if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
        uses: softprops/action-gh-release@v2
        with:
          tag_name: latest
          name: Latest (unreleased)
          prerelease: true
          files: artifacts/easy-db-lab-dashboards.zip
          fail_on_unmatched_files: true
          body: |
            Rolling pre-release with the latest dashboards from the main branch.
            This is updated automatically on every push to main.
```

## 2. Documentation

- [x] 2.1 Add a `docs/reference/dashboards.md` page documenting:
  - The dashboard artifact is published as `easy-db-lab-dashboards.zip` on every versioned GitHub Release
  - Download URL pattern for latest pre-release: `https://github.com/rustyrazorblade/easy-db-lab/releases/download/latest/easy-db-lab-dashboards.zip`
  - Download URL pattern for a specific version: `https://github.com/rustyrazorblade/easy-db-lab/releases/download/v<VERSION>/easy-db-lab-dashboards.zip`
  - How to import into an external Grafana instance (download zip, extract, use Grafana's dashboard import UI)
  - Datasource requirement: dashboards default to a datasource named `VictoriaMetrics`; users can change this via the datasource dropdown in Grafana
  - List of included dashboards and what each covers

- [x] 2.2 Add `dashboards.md` to the mdbook `SUMMARY.md` under the Reference section
